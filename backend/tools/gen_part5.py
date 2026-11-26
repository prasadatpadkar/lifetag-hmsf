# ----------------------------------------------------------------------------- simulation
def active_at(pi, off):
    return {c for c, s in P[pi]["_starts"].items() if s <= off}

def outcome_text(unit, v):
    if unit == "BP":
        ok = v["systolic"] < 140 and v["diastolic"] < 90
        return "Latest BP %d/%d mmHg (%s)." % (v["systolic"], v["diastolic"], "at target" if ok else "above target")
    if unit == "SUGAR" and "bloodSugar" in v:
        return "Latest %s sugar %d mg/dL; diabetes %s." % (v.get("sugarType", "fasting").lower(), v["bloodSugar"], "well controlled" if v["bloodSugar"] < 150 else "needs optimisation")
    if unit == "SPO2":
        return "SpO2 %d%% on room air; symptoms controlled on current inhalers." % v["spo2"]
    return "Symptoms improved on current plan; weight %.1f kg." % v["weightKg"]

def visit_note(cond, v):
    bits = ["BP %d/%d" % (v["systolic"], v["diastolic"]), "pulse %d" % v["pulse"]]
    if "bloodSugar" in v: bits.append("%s sugar %d" % (v.get("sugarType", "Fasting").lower(), v["bloodSugar"]))
    tail = {"HTN": "Continue antihypertensives, salt restriction.", "T2DM": "Continue diabetic medicines and diet.",
            "CAD": "Continue antiplatelet and statin; no angina.", "ASTHMA": "Review inhaler technique.",
            "OA_KNEE": "Continue physiotherapy and analgesics as needed.", "MIGRAINE": "Headache diary reviewed.",
            "THYROID": "Continue levothyroxine; recheck TSH.", "CKD": "Renal diet reinforced; avoid NSAIDs.",
            "COPD": "Continue inhalers; pulmonary rehab.", "GERD": "Dietary measures; PPI course.",
            "ANEMIA": "Continue iron; recheck Hb.", "BACKPAIN": "Physiotherapy; avoid heavy lifting.",
            "ANC": "Foetal movements normal; continue supplements."}[cond]
    return ", ".join(bits) + ". " + tail

def chronic_course(pi, cond, start, end_off=None, ga_start=None):
    prof, p = CH[cond], P[pi]
    dept = pick(prof["dept"])
    doc = doctor_for(dept, start)
    limit = end_off if end_off is not None else 0
    offs, off = [], start
    while off < limit:
        offs.append(off)
        off += R.randint(*prof["interval"])
    upcoming = off if (end_off is None and off <= 45) else None
    n = len(offs)
    done = []  # (off, di, vitals)
    for k, o in enumerate(offs):
        di = doc if (chance(0.75) and not (doc in DOC_ON_LEAVE and o > -45)) else doctor_for(dept, o)
        if chance(0.04) and k > 0:
            place_appt(pi, di, o, prof["reason"], "Cancelled", "Patient did not attend; rescheduled.")
            continue
        t = k / float(max(1, n - 1))
        act = active_at(pi, o) | {cond}
        v = vitals_for(pi, o, act, t)
        ai = place_appt(pi, di, o, prof["reason"] if k else prof["reason"].replace("follow-up", "- first consultation").replace("review", "- first consultation"), "Completed", visit_note(cond, v))
        if ai is None:
            continue
        a = APPT[ai]
        add_vit(pi, a["off"], a["time"], pick(NURSES), v)
        rids = []
        for key, every in prof["tests"]:
            if (every == 99 and k == 0) or (every != 99 and k % every == 0):
                ctx = dict(p=p, conds=act, t=t, acute=None, ga_weeks=int(clamp(8 + (o - start) / 7.0, 8, 40)))
                rep = make_report(key, ctx, a["off"], di); rep["p"] = pi
                REP.append(rep); rids.append(len(REP) - 1)
        done.append((a["off"], di, v))
        EVENTS.append(dict(kind="OPD", p=pi, appt=ai, reports=rids, off=a["off"], di=di, chronic=True))
    if upcoming is not None:
        di = doc if doc not in DOC_ON_LEAVE else doctor_for(dept, upcoming)
        place_appt(pi, di, upcoming, prof["reason"], "Confirmed" if chance(.7) else "Pending", None, step=1)
    if not done:
        return
    lastv = done[-1][2]
    follow = upcoming if upcoming is not None else done[-1][0] + R.randint(*prof["interval"])
    cost = lambda: R.randint(*prof["med_cost"])
    def trt(meds, s, e, status, outcome):
        TRT.append(dict(p=pi, d=doc, adm=None, diagnosis=prof["name"], code=prof["code"], type="Medication", procedure=None,
                        plan=prof["plan"], meds=meds, start=s, end=e, status=status, outcome=outcome, followUp=follow,
                        notes=None, procCost=0, medCost=cost(), billed=True))
    if cond == "ANC" and end_off is not None:
        trt(prof["meds1"], done[0][0], end_off + 4, "Completed", "Delivered at LifeTag; mother and baby well.")
    elif prof.get("escalate") and len(done) >= 4:
        mid = len(done) // 2
        trt(prof["meds0"], done[0][0], done[mid][0] - 1, "Completed", "Target not reached - regimen intensified.")
        trt(prof["meds1"], done[mid][0], None, "Ongoing", outcome_text(prof["out_unit"], lastv))
    else:
        trt(prof["meds1"], done[0][0], None, "Ongoing", outcome_text(prof["out_unit"], lastv))
    P[pi].setdefault("_chronic_names", []).append(SHORT[cond])

def eligible_eps(p):
    age = p["age"]
    keys = EP_WEIGHTS_CHILD if age < 15 else EP_WEIGHTS_ADULT
    out = []
    for k in keys:
        e = EPISODES[k]
        if not (e["ages"][0] <= age <= e["ages"][1]) or e["weight"] <= 0:
            continue
        if k == "ASTHMA_EXAC" and "ASTHMA" not in p["_starts"]:
            continue
        if k == "ANGINA_OBS" and "CAD" not in p["_starts"]:
            continue
        out.append((k, e["weight"]))
    return out

def episode(pi, key, off, mode="past", bed=None):
    ep, p = EPISODES[key], P[pi]
    di = doctor_for(ep["dept"], off)
    admit = ep["admit"] >= 1.0 or chance(ep["admit"])
    if mode in ("current", "recent"):
        admit = True
    days = R.randint(*ep["days"]) if admit else 0
    if mode == "recent":
        off = -days - R.randint(1, 3)
    dis = None if (mode == "current" or not admit) else off + days
    if dis is not None and dis > -1:
        dis = -1
    active = active_at(pi, off)
    ai = place_appt(pi, di, off, ep["reason"], "Completed", ep["name"] + " - assessed, investigations ordered.")
    if ai is None:
        return
    off = APPT[ai]["off"]
    v = vitals_for(pi, off, active, 0.8, acute=key, recover=0.0)
    add_vit(pi, off, APPT[ai]["time"], pick(NURSES), v)
    ctx = dict(p=p, conds=active, t=0.7, acute=key, ga_weeks=39)
    rids = []
    for rk in ep["reports"]:
        rep = make_report(rk, ctx, off, di); rep["p"] = pi
        REP.append(rep); rids.append(len(REP) - 1)
    if admit and days >= 3 and key in ("DENGUE", "PNEUMONIA", "TYPHOID"):
        ctx2 = dict(ctx, t=0.95)
        rep = make_report("CBC", ctx2, off + 2, di); rep["p"] = pi
        REP.append(rep); rids.append(len(REP) - 1)
    adm_i = None
    if admit:
        ward = pick(ep["wards"])
        if p["age"] < 14 and ward in ("General Ward", "Semi-Private", "Private"):
            ward = "Pediatric"
        lab = alloc_bed(ward, off, dis, want=bed)
        if lab is None:
            for w in ("Semi-Private", "General Ward", "Private"):
                lab = alloc_bed(w, off, dis)
                if lab: break
        b = BEDS[BED_IDX[lab]]
        ADM.append(dict(p=pi, d=di, bed=BED_IDX[lab], ward=b["roomType"], room=b["roomNumber"], bedNo=b["bedNumber"], rate=b["chargePerDay"],
                        admit=off, discharge=dis, reason=ep["reason"], diagnosis=ep["name"],
                        status="Discharged" if dis is not None else "Admitted",
                        summary=("Admitted with %s. Managed as per protocol. %s Advised follow-up with %s." % (ep["name"].lower(), ep["outcome"], dname(di))) if dis is not None else None,
                        billed=False))
        adm_i = len(ADM) - 1
        total = days if dis is not None else max(1, -off)
        nread = min(14, total * 2)
        for i in range(nread):
            rec = min(1.0, (i + 1) / float(nread)) if dis is not None else min(0.65, (i + 1) / float(nread))
            o = off + i // 2
            if o > -1 and dis is not None: o = -1
            vv = vitals_for(pi, o, active, 0.8, acute=key, recover=rec)
            add_vit(pi, min(o, 0), "%02d:00" % (8 if i % 2 == 0 else 20), pick(NURSES), vv)
    proc = ep["surgery"]
    pcost = R.randint(*proc[1]) if proc else 0
    mcost = R.randint(*ep["med_cost"])
    cur = dis is None and admit
    tstart_status = "Ongoing" if cur else "Completed"
    follow = (dis + R.randint(8, 14)) if dis is not None else off + R.randint(7, 20)
    t0 = len(TRT)
    TRT.append(dict(p=pi, d=di, adm=adm_i, diagnosis=ep["name"], code=ep["code"], type=ep["ttype"], procedure=proc[0] if proc else None,
                    plan="%s Medications as listed; wound/line care, diet and activity advice given." % ep["reason"] if admit else "Symptomatic treatment, hydration and rest; return if symptoms worsen.",
                    meds=ep["meds"], start=off, end=dis if dis is not None else (None if cur else off + R.randint(3, 10)),
                    status=tstart_status, outcome=("Under treatment - improving." if cur else ep["outcome"]), followUp=follow if follow > 0 or True else None,
                    notes=None, procCost=pcost, medCost=mcost, billed=False))
    tids = [t0]
    if key in ("MI", "STROKE") and not cur:
        TRT.append(dict(p=pi, d=di, adm=None, diagnosis="Secondary prevention after " + ("myocardial infarction" if key == "MI" else "stroke"),
                        code="Z86.7" if key == "MI" else "Z86.73", type="Medication", procedure=None,
                        plan="Dual antiplatelet therapy, high-intensity statin, BP and sugar control, cardiac/neuro rehabilitation, absolute tobacco avoidance.",
                        meds=[M("Aspirin", "75 mg", "Once daily"), M("Atorvastatin", "40 mg", "At night"), M("Metoprolol succinate", "25 mg", "Once daily")],
                        start=dis, end=None, status="Ongoing", outcome="Stable on secondary prevention.", followUp=dis + 30, notes=None,
                        procCost=0, medCost=R.randint(900, 1800), billed=True))
    # follow-up visit
    if admit and dis is not None:
        foff = dis + R.randint(8, 14)
        if foff < 0:
            fi = place_appt(pi, di, foff, "Post-discharge follow-up", "Completed", "Wound/clinical review satisfactory. Continue medicines; routine care.", step=1)
            if fi is not None:
                fv = vitals_for(pi, APPT[fi]["off"], active, 0.5)
                add_vit(pi, APPT[fi]["off"], APPT[fi]["time"], pick(NURSES), fv)
                EVENTS.append(dict(kind="OPD", p=pi, appt=fi, reports=[], off=APPT[fi]["off"], di=di, chronic=False))
        else:
            place_appt(pi, di, foff, "Post-discharge follow-up", "Confirmed" if chance(.7) else "Pending", None, step=1)
    blood_units = 0
    if admit and chance(ep["blood"]):
        blood_units = R.randint(1, 3)
        BLOODREQ.append(dict(p=pi, group=p["bloodGroup"], units=blood_units, ward=ADM[adm_i]["ward"], status="Fulfilled" if not cur else "Requested", off=off))
    EVENTS.append(dict(kind="IPD" if admit else "OPD", p=pi, appt=ai, reports=rids, treatments=tids, adm=adm_i, off=off, dis=dis,
                       di=di, blood=blood_units, ep=key, current=cur, chronic=False))

# ---- run the simulation
R_FOR = R
for pi, p in enumerate(P):
    forced = p["_forced"]
    deliv = [f for f in forced if f[0] == "DELIVERY"]
    for cond, start in sorted(p["_starts"].items(), key=lambda kv: kv[1]):
        if cond == "ANC" and deliv:
            chronic_course(pi, cond, start, end_off=deliv[0][1] - 4)
        else:
            chronic_course(pi, cond, start)
    for key, off, mode, bed in forced:
        episode(pi, key, off, mode, bed)

# random acute episodes (past)
ADMITTED_CURRENT = 0
for pi, p in enumerate(P):
    if p["_forced"]:
        continue
    n = wchoice([(1, .35), (2, .4), (3, .25)]) if p["age"] < 15 else wchoice([(0, .3), (1, .4), (2, .3)])
    el = eligible_eps(p)
    for _ in range(n):
        key = wchoice(el)
        off = -R.randint(5, 800)
        if p["age"] < 18 and key == "FRACTURE": pass
        episode(pi, key, off, "past")

# currently admitted + recently discharged (random, unforced patients)
cands = [i for i, p in enumerate(P) if not p["_forced"] and p["status"] == "Active"]
R.shuffle(cands)
cur_plan = [("PNEUMONIA", 3), ("STROKE", 1), ("MI", 1), ("FRACTURE", 3), ("DENGUE", 3), ("APPENDICITIS", 2), ("GASTRO", 1), ("TYPHOID", 2),
            ("KIDNEY_STONE", 1), ("GALLSTONES", 1), ("HERNIA", 1), ("ASTHMA_EXAC", 1), ("DELIVERY", 2), ("TKR", 1)]
for key, cnt in cur_plan:
    done_c = 0
    for pi in list(cands):
        p, e = P[pi], EPISODES[key]
        ok = e["ages"][0] <= p["age"] <= e["ages"][1]
        if key == "DELIVERY": ok = p["gender"] == "Female" and 20 <= p["age"] <= 36
        if key == "ASTHMA_EXAC": ok = p["age"] < 15
        if key == "TKR": ok = p["age"] >= 58
        if key in ("STROKE", "MI"): ok = p["age"] >= 45
        if ok:
            cands.remove(pi)
            episode(pi, key, -R.randint(1, 6), "current")
            done_c += 1
            if done_c >= cnt: break
for key in ["APPENDICITIS", "FRACTURE", "PNEUMONIA", "DENGUE", "GALLSTONES"]:
    for pi in list(cands):
        p, e = P[pi], EPISODES[key]
        if e["ages"][0] <= p["age"] <= e["ages"][1]:
            cands.remove(pi)
            episode(pi, key, -5, "recent")
            break

# today's schedule + extra upcoming OPD appointments
TODAY_REASONS = ["General check-up", "Follow-up consultation", "Fever and cold", "Report review", "Routine check-up", "Pain evaluation"]
act_p = [i for i, p in enumerate(P) if p["status"] == "Active"]
for _ in range(34):
    pi = pick(act_p)
    di = pick([i for i in range(len(DOCTORS)) if i not in DOC_ON_LEAVE and DOCTORS[i][7] != "Off Duty"])
    if (pi, 0) in SLOT_PAT: continue
    ai = place_appt(pi, di, 0, pick(TODAY_REASONS), "Pending", None, step=1)
    if ai is None: continue
    h = int(APPT[ai]["time"][:2])
    APPT[ai]["status"] = wchoice([("Completed", .75), ("Waiting", .15), ("Confirmed", .1)]) if h < 12 else wchoice([("Confirmed", .55), ("Waiting", .15), ("Pending", .3)])
for _ in range(70):
    pi = pick(act_p)
    di = pick([i for i in range(len(DOCTORS)) if i not in DOC_ON_LEAVE and DOCTORS[i][7] != "Off Duty"])
    place_appt(pi, di, R.randint(1, 21), pick(TODAY_REASONS), "Confirmed" if chance(.6) else "Pending", None, step=1)

# chronic conditions text + registration offsets
for pi, p in enumerate(P):
    names = p.pop("_chronic_names", [])
    p["chronicConditions"] = ", ".join(names) if names else None
    firsts = [a["off"] for a in APPT if a["p"] == pi] if False else []
first_off = {}
for a in APPT:
    first_off[a["p"]] = min(first_off.get(a["p"], 10 ** 6), a["off"])
for pi, p in enumerate(P):
    f = first_off.get(pi, -30)
    p["regOffset"] = min(f, 0) - R.randint(0, 20)
