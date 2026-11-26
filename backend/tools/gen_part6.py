# ----------------------------------------------------------------------------- insurance, schemes
PROVIDERS = [("Star Health Insurance", "1800-102-4477"), ("HDFC ERGO Health", "1800-266-0700"), ("ICICI Lombard", "1800-266-9725"),
             ("Niva Bupa", "1800-258-5970"), ("Care Health Insurance", "1800-102-4488"), ("Bajaj Allianz", "1800-209-5858"),
             ("New India Assurance", "1800-209-1415"), ("United India Insurance", "1800-425-3333")]
POLICIES = []
for pi, p in enumerate(P):
    if p["existingEmail"] or p["age"] < 5 or not chance(0.42):
        continue
    prov = pick(PROVIDERS)[0]
    vfrom = -R.randint(500, 1000)
    vto = vfrom + 365 * R.choice([2, 3])
    POLICIES.append(dict(p=pi, provider=prov, number="%s-%d-%06d" % ("".join(w[0] for w in prov.split()[:2]).upper(), 2024 + R.randint(0, 2), R.randint(1, 999999)),
                         coverage=R.choice([300000, 500000, 700000, 1000000]), ctype=R.choice(["Individual", "Family Floater", "Group / Corporate"]),
                         vfrom=vfrom, vto=vto, status="Active" if vto >= 0 else "Expired"))
POLICY_OF = {pl["p"]: pl for pl in POLICIES}

SCHEMES_NEW = [
 ("Mahatma Jyotiba Phule Jan Arogya Yojana", "State health insurance for low-income families in Maharashtra, covering 1,000+ procedures.", "Yellow/orange ration card holders", "Cashless cover up to Rs. 5,00,000 per family per year", "Government"),
 ("CGHS", "Central Government Health Scheme for central government employees and pensioners.", "Central government employees and pensioners", "Cashless treatment at CGHS package rates", "Government"),
 ("ESIS Medical Benefit", "Employees' State Insurance medical care for insured workers.", "Insured workers earning up to Rs. 21,000 per month", "Full medical care for insured person and family", "Government"),
 ("Janani Suraksha Yojana", "Safe-motherhood scheme promoting institutional delivery.", "Pregnant women from low-income households", "Free delivery care and cash incentive", "Government"),
 ("Rashtriya Bal Swasthya Karyakram", "Child health screening and early intervention programme.", "Children 0-18 years", "Free screening and treatment for defects, diseases, deficiencies and delays", "Government"),
 ("Employee Wellness Package", "Hospital package for hospital staff and dependants.", "Hospital employees and dependants", "15% discount on all services", "Hospital"),
]
# scheme index: 0 Ayushman Bharat, 1 Senior Citizen (both already exist), 2.. = SCHEMES_NEW
ENROLL = []
for pi, p in enumerate(P):
    if p["existingEmail"] or not chance(0.23):
        continue
    if p["age"] >= 60 and chance(.5): sc = 1
    elif p["age"] < 15: sc = pick([0, 6])
    elif p["gender"] == "Female" and 20 <= p["age"] <= 36 and chance(.3): sc = 5
    else: sc = wchoice([(0, 5), (2, 4), (3, 2), (4, 2), (7, 1)])
    st = wchoice([("Verified", .65), ("Pending Verification", .25), ("Rejected", .10)])
    off = -R.randint(30, 600)
    ENROLL.append(dict(p=pi, scheme=sc, off=off, status=st, aadhaar="0000" + "".join(str(R.randrange(10)) for _ in range(8)),
                       card="BEN%09d" % R.randint(1, 999999999), by=pick(STAFF_RECEPTION)[0] if st != "Pending Verification" else None,
                       vOff=off + R.randint(1, 6) if st != "Pending Verification" else None))
VERIFIED = {e["p"]: e for e in ENROLL if e["status"] == "Verified" and e["scheme"] in (0, 2, 3, 4)}
PACKAGES = {"APPENDICITIS": ("SG-GEN-011", "Appendectomy"), "GALLSTONES": ("SG-GEN-024", "Cholecystectomy"), "HERNIA": ("SG-GEN-031", "Hernioplasty"),
            "FRACTURE": ("OR-TRM-008", "Fracture fixation"), "TKR": ("OR-JNT-002", "Total knee replacement"), "MI": ("CD-ACS-004", "Acute MI with PTCA"),
            "STROKE": ("NR-STK-001", "Acute stroke management"), "PNEUMONIA": ("MD-RSP-006", "Pneumonia management"), "DENGUE": ("MD-INF-003", "Dengue fever"),
            "DELIVERY": ("OB-DEL-001", "Normal delivery"), "TYPHOID": ("MD-INF-005", "Enteric fever"), "KIDNEY_STONE": ("UR-CAL-002", "Ureteric calculus"),
            "GASTRO": ("MD-GIT-001", "Acute gastroenteritis"), "ASTHMA_EXAC": ("MD-RSP-002", "Acute asthma"), "ANGINA_OBS": ("CD-ACS-001", "Unstable angina")}

# ----------------------------------------------------------------------------- billing
D = Decimal
def calc(items, discount):
    sub = D(0); tax = D(0)
    for it in items:
        line = (D(it["qty"]) * D(it["amount"])).quantize(D("0.01"), ROUND_HALF_UP)
        tx = (line * D(it["taxPct"]) / D(100)).quantize(D("0.01"), ROUND_HALF_UP)
        sub += line; tax += tx
    return sub, tax, sub - D(discount) + tax

def it(cat, desc, qty, amount, tax=0):
    return dict(category=cat, desc=desc, qty=qty, amount=int(amount), taxPct=tax)

def rep_cat(r):
    return {"Radiology": "Radiology", "Cardiac": "Cardiac Diagnostics", "Special Tests": "Special Investigations"}.get(r["category"], "Laboratory")

METHODS = [("Cash", .33), ("UPI", .37), ("Card", .2), ("Net Banking", .1)]
def pay_ref(m):
    return {"UPI": "UPI%012d" % R.randint(1, 10**12 - 1), "Card": "CARD-%04d" % R.randint(1, 9999), "Net Banking": "NB%09d" % R.randint(1, 10**9 - 1)}.get(m, "")

BILLS, CLAIMS, SCLAIMS = [], [], []
first_bill = set()

def make_bill(ev, items, ipd):
    pi, p = ev["p"], P[ev["p"]]
    off = ev["dis"] if (ipd and ev.get("dis") is not None) else ev["off"]
    di = ev["di"]
    sub, tax, amt0 = calc(items, 0)
    discount = 0
    if p["age"] >= 60 and chance(0.4):
        discount = int(sub * D("0.05" if ipd else "0.10"))
        note = "Senior citizen discount applied."
    elif chance(0.05):
        discount = int(sub * D("0.05")); note = "Management discount."
    else:
        note = None
    sub, tax, amount = calc(items, discount)
    covered, cnote = 0, None
    if ipd and amount > 0:
        adm = ADM[ev["adm"]]
        src = None
        if pi in VERIFIED and chance(.75): src = "scheme"
        else:
            pol = POLICY_OF.get(pi)
            if pol and pol["vfrom"] <= adm["admit"] <= pol["vto"] and chance(.85): src = "insurance"
        if src:
            if src == "scheme":
                status = wchoice([("Approved", .5), ("Under Govt Review", .25), ("Claim Submitted", .15), ("Rejected", .10)])
            else:
                status = wchoice([("Approved", .62), ("Under Review", .2), ("Submitted", .12), ("Rejected", .06)])
            sub_off = min(-1, off + R.randint(1, 3))
            resp = sub_off + R.randint(5, 21) if status in ("Approved", "Rejected") else None
            if resp is not None and resp > -1:
                status, resp = ("Under Govt Review" if src == "scheme" else "Under Review"), None
            if src == "scheme":
                claim_amt = min(int(amount * D(R.choice(["0.9", "0.95", "1"]))), 500000)
            else:
                claim_amt = min(int(amount * D("0.8")) // 100 * 100, POLICY_OF[pi]["coverage"])
            claim_amt = max(0, min(claim_amt, int(amount)))
            if claim_amt > 0:
                covered = 0 if status == "Rejected" else claim_amt
                cnote = ("%s (%s)" % ("Ayushman/State scheme cashless" if src == "scheme" else POLICY_OF[pi]["provider"] + " " + POLICY_OF[pi]["number"], status.lower())) if covered else None
                rec = dict(bill=len(BILLS), p=pi, amount=claim_amt, status=status, sub=sub_off, resp=resp, ep=ev.get("ep"))
                if src == "scheme":
                    rec["enroll"] = VERIFIED[pi]; SCLAIMS.append(rec)
                else:
                    rec["policy"] = POLICY_OF[pi]; CLAIMS.append(rec)
    payable = amount - covered
    age = -off
    st = wchoice([("Paid", .9), ("Partial", .06), ("Unpaid", .04)]) if age > 45 else wchoice([("Paid", .72), ("Partial", .16), ("Unpaid", .12)]) if age > 14 else wchoice([("Paid", .5), ("Partial", .25), ("Unpaid", .25)])
    if payable <= 0:
        st = "Paid"
    payments, paid, method = [], 0, None
    if st != "Unpaid" and payable > 0:
        method = wchoice(METHODS)
        if st == "Paid":
            total = payable
        else:
            total = max(100, min(int(payable) - 100, round_to(float(payable) * R.uniform(.3, .8), 100)))
        if ipd and total >= 2000 and ev.get("dis") is not None:
            adv = min(int(total) - 100, round_to(float(total) * R.uniform(.3, .5), 100))
            m2 = wchoice(METHODS)
            payments.append(dict(off=ev["off"], amount=adv, method=m2, ref=pay_ref(m2)))
            payments.append(dict(off=min(-1, ev["dis"]), amount=total - adv, method=method, ref=pay_ref(method)))
        else:
            payments.append(dict(off=min(0, off), amount=total, method=method, ref=pay_ref(method)))
        paid = total
    if st == "Paid" and payable <= 0:
        paid = 0
    bill = dict(p=pi, d=di, adm=ev["adm"] if ipd else None, off=off, items=items, discount=discount, covered=covered, cnote=cnote,
                paid=paid, payments=payments, method=method, notes=note, dueOff=off + (7 if ipd else 15), status=st)
    BILLS.append(bill)
    return len(BILLS) - 1

def opd_items(ev, first):
    di, items = ev["di"], []
    if first:
        items.append(it("Registration", "Patient registration & OPD card", 1, 100))
    items.append(it("Doctor Fees", "Consultation - %s (%s)" % (dname(di), DOCTORS[di][2]), 1, DOCTORS[di][5]))
    for ri in ev["reports"]:
        r = REP[ri]
        items.append(it(rep_cat(r), r["title"], 1, r["cost"]))
    if ev.get("chronic") and chance(.5):
        items.append(it("Pharmacy", "Medicines dispensed (30-day supply)", 1, R.randint(250, 1800), 5))
    return items

def ipd_items(ev):
    adm = ADM[ev["adm"]]
    days = max(1, (ev["dis"] - adm["admit"]) if ev["dis"] is not None else -adm["admit"])
    di, items = ev["di"], []
    items.append(it("Registration", "IPD admission & registration charges", 1, 500))
    items.append(it("Room & Bed", "%s - bed %s (%d day%s)" % (adm["ward"], adm["bedNo"], days, "" if days == 1 else "s"), days, adm["rate"]))
    nurse = {"ICU": 1500, "Private": 600, "Semi-Private": 450}.get(adm["ward"], 350)
    items.append(it("Nursing & Care", "Nursing & care charges", days, nurse))
    fee = DOCTORS[di][5]
    items.append(it("Doctor Fees", "Admission consultation - %s" % dname(di), 1, fee))
    items.append(it("Doctor Fees", "Daily doctor visits - %s" % dname(di), days, round_to(fee * 0.6, 10)))
    for ri in ev["reports"]:
        r = REP[ri]
        items.append(it(rep_cat(r), r["title"], 1, r["cost"]))
    for ti in ev["treatments"]:
        t = TRT[ti]
        if t["procCost"] > 0:
            items.append(it("Procedures & Surgery", t["procedure"] or t["type"], 1, t["procCost"]))
            if t["type"] in ("Surgery", "Procedure"):
                items.append(it("Procedures & Surgery", "Anaesthesia & surgical team charges", 1, round_to(t["procCost"] * 0.12, 100)))
                items.append(it("Operation Theatre", "OT charges - " + (t["procedure"] or t["type"]), 1, round_to(t["procCost"] * 0.15, 100)))
        if t["medCost"] > 0:
            items.append(it("Pharmacy", "Medicines & IV fluids - " + t["diagnosis"], 1, t["medCost"], 5))
    items.append(it("Miscellaneous", "Consumables, disposables & dressings", 1, R.randint(800, 4500), 12))
    if ev.get("blood"):
        items.append(it("Blood Bank", "Packed red cells (1 unit)", ev["blood"], 1500))
    if chance(.12):
        items.append(it("Ambulance", "Ambulance transfer", 1, 1500))
    return items

for ev in sorted(EVENTS, key=lambda e: e["off"]):
    pi = ev["p"]
    if ev["kind"] == "OPD":
        recent = ev["off"] > -6
        if (recent and chance(.6)) or chance(.04):
            continue
        first = pi not in first_bill
        first_bill.add(pi)
        bi = make_bill(ev, opd_items(ev, first), False)
        APPT[ev["appt"]]["billed"] = True
        for ri in ev["reports"]: REP[ri]["billed"] = True
    else:
        if ev["current"] or (ev["dis"] is not None and ev["dis"] > -4):
            continue  # still admitted / just discharged: left un-invoiced so "Import charges" has something to offer
        first_bill.add(pi)
        bi = make_bill(ev, ipd_items(ev), True)
        APPT[ev["appt"]]["billed"] = True
        ADM[ev["adm"]]["billed"] = True
        for ri in ev["reports"]: REP[ri]["billed"] = True
        for ti in ev["treatments"]: TRT[ti]["billed"] = True
    BILLS[bi]["ep"] = ev.get("ep")
# every non-recent OPD treatment / appointment billed flag stays as set; a few cancelled invoices
for b in R.sample(BILLS, 12):
    b["status"] = "Cancelled"; b["paid"] = 0; b["payments"] = []; b["covered"] = 0; b["cnote"] = None
# claims whose bill was cancelled must go away
cancelled = {i for i, b in enumerate(BILLS) if b["status"] == "Cancelled"}
CLAIMS = [c for c in CLAIMS if c["bill"] not in cancelled]
SCLAIMS = [c for c in SCLAIMS if c["bill"] not in cancelled]
# bill ids are assigned in this order by the loader; sort so older invoices get lower numbers
order = sorted(range(len(BILLS)), key=lambda i: BILLS[i]["off"])
remap = {old: new for new, old in enumerate(order)}
BILLS = [BILLS[i] for i in order]
for c in CLAIMS + SCLAIMS:
    c["bill"] = remap[c["bill"]]
