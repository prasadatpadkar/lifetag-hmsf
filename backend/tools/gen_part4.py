# ----------------------------------------------------------------------------- state
P, VIT, REP, TRT, ADM, APPT, EVENTS, BLOODREQ = [], [], [], [], [], [], [], []
SLOT_DOC, SLOT_PAT = set(), set()
FAR = 10 ** 6

# ----------------------------------------------------------------------------- beds
BEDS = []
EXISTING_BEDS = {"GEN-001", "GEN-002", "GEN-003", "ICU-001", "ICU-002", "MAT-001", "MAT-002", "PED-001"}
MAINT = {"GEN-024", "PVT-012", "ICU-012"}
def _beds(prefix, n, ward, rate):
    for i in range(1, n + 1):
        lab = "%s-%03d" % (prefix, i)
        BEDS.append(dict(roomNumber=lab, bedNumber=lab, roomType=ward, chargePerDay=rate,
                         status="Maintenance" if lab in MAINT else "Available", existing=lab in EXISTING_BEDS))
_beds("GEN", 24, "General Ward", 1200); _beds("SPR", 12, "Semi-Private", 2500); _beds("PVT", 12, "Private", 4000)
_beds("ICU", 12, "ICU", 4500); _beds("MAT", 8, "Maternity", 2000); _beds("PED", 8, "Pediatric", 1500); _beds("EMR", 6, "Emergency", 1800)
BED_IDX = {b["roomNumber"]: i for i, b in enumerate(BEDS)}
BED_BUSY = {b["roomNumber"]: [] for b in BEDS}

def alloc_bed(ward, s, e, want=None):
    e = FAR if e is None else e
    labels = [want] if want else [b["roomNumber"] for b in BEDS if b["roomType"] == ward and b["roomNumber"] not in MAINT]
    if not want:
        R.shuffle(labels)
    for lab in labels:
        if all(e < a or s > b for a, b in BED_BUSY[lab]):
            BED_BUSY[lab].append((s, e))
            return lab
    return None

# ----------------------------------------------------------------------------- slots
def skip_sun(off, step):
    return off + step if weekday(off) == 6 else off

def place_appt(pi, di, off, reason, status, notes=None, step=-1):
    """Create an appointment on `off` (shifting by `step` days if the patient/doctor slot is taken)."""
    for _ in range(40):
        off = skip_sun(off, step)
        if (pi, off) in SLOT_PAT:
            off += step
            continue
        for _t in range(60):
            h, m = R.randrange(9, 17), pick([0, 15, 30, 45])
            if h == 13 or (h == 16 and m > 15):
                continue
            if (di, off, h, m) not in SLOT_DOC:
                SLOT_DOC.add((di, off, h, m))
                SLOT_PAT.add((pi, off))
                APPT.append(dict(p=pi, d=di, off=off, time="%02d:%02d" % (h, m), reason=reason, status=status,
                                 notes=(notes or "")[:250] or None, billed=False))
                return len(APPT) - 1
        off += step
    return None

# ----------------------------------------------------------------------------- vitals
def baseline(age):
    if age < 5: return 92, 58, 104, 22
    if age < 12: return 100, 62, 88, 20
    if age < 18: return 108, 68, 78, 17
    if age < 50: return 118, 76, 74, 16
    if age < 65: return 126, 80, 76, 16
    return 132, 82, 74, 17

def vitals_for(pi, off, active, t, acute=None, recover=0.0):
    p = P[pi]
    s, d, pulse, rr = baseline(p["age"])
    s += R.gauss(0, 5); d += R.gauss(0, 3.5); pulse += R.gauss(0, 6)
    temp, spo2 = R.gauss(36.7, 0.2), R.gauss(98, 0.8)
    if "HTN" in active:
        c = min(1.0, t * 1.4)
        s, d = 154 - 22 * c + R.gauss(0, 7), 97 - 13 * c + R.gauss(0, 4.5)
    if "CKD" in active: s += 8
    if "CAD" in active: pulse = R.gauss(64, 5)
    if "THYROID" in active: pulse -= 5
    if "COPD" in active: spo2, rr = R.gauss(92.5, 1.5), rr + 3
    if "ANC" in active: s, d = R.gauss(112, 6), R.gauss(72, 4)
    base = dict(s=s, d=d, pulse=pulse, temp=temp, spo2=spo2, rr=rr)
    if acute:
        a = dict(base)
        if acute in ("FEVER_VIRAL", "URTI", "GASTRO", "UTI", "DENGUE", "TYPHOID", "PNEUMONIA", "SINUSITIS"):
            a.update(temp=R.gauss(38.8, 0.6), pulse=pulse + 18, rr=rr + 4)
        if acute == "PNEUMONIA": a.update(spo2=R.gauss(91, 2.2), rr=26, temp=R.gauss(38.9, 0.5))
        if acute == "DENGUE": a.update(temp=R.gauss(39.2, 0.5), s=s - 12, d=d - 8)
        if acute == "ASTHMA_EXAC": a.update(spo2=R.gauss(92, 1.5), rr=30, pulse=pulse + 25)
        if acute == "MI": a.update(s=R.gauss(150, 14), d=R.gauss(92, 8), pulse=R.gauss(98, 10), spo2=R.gauss(94, 1.5))
        if acute == "ANGINA_OBS": a.update(s=R.gauss(146, 12), d=R.gauss(90, 8), pulse=R.gauss(90, 8))
        if acute == "STROKE": a.update(s=R.gauss(178, 14), d=R.gauss(102, 8), pulse=R.gauss(88, 8))
        if acute == "APPENDICITIS": a.update(temp=R.gauss(38.2, 0.4), pulse=pulse + 20)
        if acute in ("FRACTURE", "KIDNEY_STONE"): a.update(pulse=pulse + 14, s=s + 10, d=d + 6)
        for k in base:
            base[k] = a[k] + (base[k] - a[k]) * recover
    s, d = base["s"], base["d"]
    d = min(d, s - 18)
    v = dict(systolic=int(clamp(round(s), 70, 230)), diastolic=int(clamp(round(d), 40, 130)),
             pulse=int(clamp(round(base["pulse"]), 48, 150)), temperatureC=round(clamp(base["temp"], 35.5, 41.0), 1),
             spo2=int(clamp(round(base["spo2"]), 80, 100)), respiratoryRate=int(clamp(round(base["rr"]), 10, 40)))
    # body measurements
    now_age = p["age"]
    if now_age < 14:
        w = p["_w"] + off * 0.007; h = p["_h"] + off * 0.02
    else:
        f = (off + 900) / 900.0
        w = p["_w"] + p["_wd"] * (f - 1.0); h = p["_h"]
    v["weightKg"] = round(clamp(w + R.gauss(0, 0.3), 3, 220), 1)
    v["heightCm"] = round(clamp(h, 45, 220), 1)
    if "T2DM" in active:
        sug = 165 - 38 * min(1.0, t * 1.3) + R.gauss(0, 18)
        typ = pick(["Fasting", "Fasting", "Post-meal"])
        if typ == "Post-meal": sug += 55
        v["bloodSugar"], v["sugarType"] = int(clamp(sug, 70, 380)), typ
    elif chance(0.3):
        v["bloodSugar"], v["sugarType"] = int(clamp(R.gauss(92, 10), 65, 140)), "Fasting"
    return v

def add_vit(pi, off, hhmm, by, v):
    rec = dict(p=pi, off=off, time=hhmm, by=by)
    rec.update(v)
    VIT.append(rec)
    return rec

# ----------------------------------------------------------------------------- patients
def make_patient(name, age, gender, blood=None, existing_email=None, username=None):
    first = name.split()[0].lower().replace("'", "")
    last = name.split()[-1].lower().replace("'", "")
    n = len(P) + 1
    p = dict(name=name, age=age, gender=gender,
             contact=rnd_phone(), emergencyContact=rnd_phone(),
             email=existing_email or "%s.%s%d@example.com" % (first, last, n),
             address="%d %s, %s" % (R.randint(1, 240), pick(STREETS), wchoice(CITIES)),
             bloodGroup=blood or wchoice(BLOOD),
             allergies=None, chronicConditions=None, regOffset=0, status="Active",
             username=None if existing_email else (username or ("%s.%s" % (first, last))),
             existingEmail=existing_email)
    if not existing_email:
        p["username"] = username or ("%s.%s" % (first, last))
    if age < 14:
        p["_h"], p["_w"] = 50 + age * 6.3 + R.gauss(0, 3), 3.5 + age * 2.9 + R.gauss(0, 1.2)
    elif gender == "Male":
        p["_h"], p["_w"] = R.gauss(171, 6), R.gauss(74, 11)
    else:
        p["_h"], p["_w"] = R.gauss(158, 5.5), R.gauss(62, 10)
    p["_wd"] = R.uniform(-4, 5)
    p["_starts"], p["_forced"], p["_endoff"] = {}, [], {}
    if chance(0.28):
        p["allergies"] = ", ".join(R.sample(ALLERGIES, R.choice([1, 1, 2])))
    P.append(p)
    return len(P) - 1

SHOW = [
 # name, age, gender, blood, {cond: start}, forced episodes [(key, off, mode, bed)]
 ("Ramesh Patil", 58, "Male", "B+", {"HTN": -780, "T2DM": -700, "CAD": -400}, [("MI", -70, "past", None)]),
 ("Sunita Joshi", 52, "Female", "A+", {"T2DM": -820, "HTN": -600, "THYROID": -500}, []),
 ("Aarav Sharma", 7, "Male", "O+", {"ASTHMA": -540}, [("ASTHMA_EXAC", -120, "past", None)]),
 ("Fatima Khan", 29, "Female", "O+", {"ANC": -230, "ANEMIA": -400}, [("DELIVERY", -20, "past", None)]),
 ("Vijay Kamble", 66, "Male", "A+", {"CKD": -700, "HTN": -760, "COPD": -480}, []),
 ("Meena Iyer", 61, "Female", "B+", {"OA_KNEE": -720}, [("TKR", -150, "past", None)]),
 ("Karan Thakur", 35, "Male", "O+", {"BACKPAIN": -300}, []),
 ("Anita Rane", 44, "Female", "AB+", {"MIGRAINE": -600}, []),
 ("Sanjay Gaikwad", 49, "Male", "O+", {"HTN": -650}, [("STROKE", -40, "past", None)]),
 ("Tanvi Desai", 14, "Female", "A+", {}, [("APPENDICITIS", -25, "past", None)]),
 ("Mohan Lal Gupta", 72, "Male", "B+", {"COPD": -800, "CAD": -700, "CKD": -420}, []),
 ("Pooja Naik", 31, "Female", "O+", {"GERD": -350}, [("DENGUE", -60, "past", None)]),
]
EXISTING = [
 ("John Doe", 34, "Male", "O+", "john.doe@lifetag.com", {"HTN": -420}, [("ANGINA_OBS", -2, "current", "GEN-001")]),
 ("Priya Kulkarni", 8, "Female", "A+", "priya.kulkarni@lifetag.com", {"ASTHMA": -360}, [("ASTHMA_EXAC", -1, "current", "PED-001")]),
 ("Arjun Nair", 47, "Male", "B-", "arjun.nair@lifetag.com", {"HTN": -600, "T2DM": -500}, [("MI", -3, "current", "ICU-001")]),
]
used_names = set()
for nm, age, g, bl, em, conds, forced in EXISTING:
    i = make_patient(nm, age, g, bl, existing_email=em); used_names.add(nm)
    P[i]["_starts"], P[i]["_forced"] = dict(conds), list(forced)
for nm, age, g, bl, conds, forced in SHOW:
    i = make_patient(nm, age, g, bl); used_names.add(nm)
    P[i]["_starts"], P[i]["_forced"] = dict(conds), list(forced)

def random_conds(age, gender):
    c = []
    def add(k, pr):
        if k not in c and chance(pr): c.append(k)
    if age < 15:
        add("ASTHMA", 0.15)
    elif age < 40:
        for k, pr in [("THYROID", .1), ("BACKPAIN", .1), ("MIGRAINE", .09), ("GERD", .1), ("HTN", .04)]: add(k, pr)
        if gender == "Female": add("ANEMIA", .16)
        if gender == "Female" and 21 <= age <= 36: add("ANC", .1)
    elif age < 60:
        for k, pr in [("HTN", .38), ("T2DM", .27), ("OA_KNEE", .1), ("THYROID", .1), ("GERD", .1), ("MIGRAINE", .05), ("BACKPAIN", .08), ("CAD", .1)]: add(k, pr)
    else:
        for k, pr in [("HTN", .6), ("T2DM", .42), ("CAD", .27), ("OA_KNEE", .27), ("COPD", .16), ("CKD", .15), ("THYROID", .08), ("GERD", .1)]: add(k, pr)
        if not c: c.append(pick(["HTN", "T2DM", "OA_KNEE"]))
    return c[:3]

TARGET = 300
while len(P) < TARGET:
    r = R.random()
    age = R.randint(1, 14) if r < .11 else R.randint(15, 39) if r < .38 else R.randint(40, 59) if r < .70 else R.randint(60, 88)
    g = pick(["Male", "Female"])
    nm = "%s %s" % (pick(MALE if g == "Male" else FEMALE), pick(SURNAMES))
    if nm in used_names:
        continue
    used_names.add(nm)
    i = make_patient(nm, age, g)
    P[i]["_starts"] = {c: (-R.randint(110, 850) if c != "ANC" else -R.randint(60, 200)) for c in random_conds(age, g)}
    if chance(0.03): P[i]["status"] = "Inactive"

# unique usernames
seen = {}
for p in P:
    if p["username"]:
        u = p["username"]
        if u in seen:
            seen[u] += 1; p["username"] = "%s%d" % (u, seen[u])
        else:
            seen[u] = 1
