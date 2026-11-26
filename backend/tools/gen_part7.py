# ----------------------------------------------------------------------------- blood bank
BLOODBANK = []
for grp, w in BLOOD:
    for _ in range(max(3, w // 5 + 2)):
        don = last = -R.randint(1, 52)
        units = R.randint(1, 9)
        exp = last + 42
        status = "Expired" if exp < 0 else ("Available" if units > 4 else "Low Stock")
        g = pick(["Male", "Female"])
        BLOODBANK.append(dict(group=grp, units=units, donor="%s %s" % (pick(MALE if g == "Male" else FEMALE), pick(SURNAMES)),
                              contact=rnd_phone(), last=last, expiry=exp, status=status))
for _ in range(40):
    pi = pick(range(len(P)))
    BLOODREQ.append(dict(p=pi, group=P[pi]["bloodGroup"], units=R.randint(1, 4), ward=pick(["General Ward", "ICU", "Emergency", "Semi-Private", "Maternity"]),
                         status=wchoice([("Fulfilled", .55), ("Requested", .2), ("Urgent", .12), ("Cancelled", .13)]), off=-R.randint(0, 120)))

# ----------------------------------------------------------------------------- feedback
GOOD = ["Very thorough consultation and explained everything clearly.", "Doctor listened patiently and the treatment worked well.", "Excellent care, short waiting time.",
        "Friendly staff and a very professional doctor.", "Clear explanation of reports and medicines. Highly recommend."]
OKAY = ["Good treatment but the waiting time was long.", "Doctor was good; billing took some time.", "Satisfied overall, consultation felt a little rushed."]
BAD = ["Waited more than an hour past my appointment time.", "Felt the doctor did not spend enough time explaining.", "Billing was confusing and took too long."]
FEEDBACK = []
for ai, a in enumerate(APPT):
    if a["status"] == "Completed" and a["off"] < -1 and chance(.12) and P[a["p"]]["status"] == "Active":
        r = wchoice([(5, .45), (4, .30), (3, .14), (2, .07), (1, .04)])
        FEEDBACK.append(dict(p=a["p"], d=a["d"], rating=r, comments=pick(GOOD if r >= 4 else OKAY if r == 3 else BAD), off=min(-1, a["off"] + R.randint(0, 3))))

# ----------------------------------------------------------------------------- staff activity + notifications
ACTS = []
actions = [("Created", "Patient: %s"), ("Updated", "Patient: %s"), ("Created", "Appointment: %s"), ("Updated", "Appointment: %s → Confirmed"),
           ("Created", "Invoice: INV-%04d — %s"), ("Updated", "Invoice: INV-%04d — %s"), ("Created", "Room & Bed: assigned bed to %s"),
           ("Updated", "Blood Bank: stock for %s"), ("Created", "Insurance: policy for %s"), ("Reset password", "Patient: %s")]
for _ in range(450):
    staff, role = (pick(STAFF_RECEPTION), "RECEPTION") if chance(.75) else (pick(STAFF_ADMINS), "ADMIN")
    act, fmt_ = pick(actions)
    nm = P[pick(range(len(P)))]["name"]
    if "INV-" in fmt_:
        details = fmt_ % (R.randint(1, len(BILLS)) + 2, nm)
    elif "%s" in fmt_ and "stock" in fmt_:
        details = fmt_ % wchoice(BLOOD)
    else:
        details = fmt_ % nm
    ACTS.append(dict(staff=staff[0], role=role, action=act, details=details, off=-R.randint(0, 60), time="%02d:%02d" % (R.randint(8, 19), R.randint(0, 59))))

NOTIFS = []
for pi in R.sample(range(len(P)), 160):
    kinds = [("Appointment reminder", "You have an appointment coming up. Please arrive 15 minutes early.", "APPOINTMENT", "patient-appointments.html"),
             ("New report available", "A new report has been added to your medical record.", "PATIENT", "patient-records.html"),
             ("New bill added", "A new bill has been added to your account.", "BILLING", "patient-billing.html"),
             ("Payment received", "We received your payment. Thank you.", "BILLING", "patient-billing.html")]
    for t_, m_, ty, ln in R.sample(kinds, R.choice([1, 2])):
        NOTIFS.append(dict(p=pi, title=t_, message=m_, type=ty, link=ln, off=-R.randint(0, 20), read=chance(.55)))

# ----------------------------------------------------------------------------- validation
def validate():
    # bed overlaps
    by = {}
    for a in ADM:
        by.setdefault(a["bed"], []).append((a["admit"], FAR if a["discharge"] is None else a["discharge"]))
    for b, iv in by.items():
        iv.sort()
        for x, y in zip(iv, iv[1:]):
            assert x[1] < y[0], ("bed overlap", BEDS[b]["roomNumber"], x, y)
    for a in ADM:
        assert a["discharge"] is None or a["discharge"] >= a["admit"], a
    # one current admission per patient
    cur = [a["p"] for a in ADM if a["status"] == "Admitted"]
    assert len(cur) == len(set(cur)), "patient admitted twice"
    # bills
    for b in BILLS:
        sub, tax, amt = calc(b["items"], b["discount"])
        assert b["discount"] <= sub, b
        assert b["covered"] <= amt
        pay = amt - b["covered"]
        assert b["paid"] <= pay + D("0.01"), (b["paid"], pay)
        assert sum(x["amount"] for x in b["payments"]) == b["paid"]
        if b["status"] == "Paid": assert b["paid"] >= pay - D("0.01") or pay <= 0
    emails = [p["email"] for p in P]; assert len(emails) == len(set(emails))
    users = [p["username"] for p in P if p["username"]]; assert len(users) == len(set(users))
    # appointments unique slots per patient/day
    seen = set()
    for a in APPT:
        k = (a["p"], a["off"]); assert k not in seen, k; seen.add(k)
    # doctor on leave not booked recently
    for a in APPT:
        if a["d"] in DOC_ON_LEAVE: assert a["off"] <= -45, a
validate()

# ----------------------------------------------------------------------------- export
def clean_patient(p):
    return {k: v for k, v in p.items() if not k.startswith("_")}

def money(x): return float(x)

OUT = dict(
    doctors=[dict(name=d[0], specialization=d[1], department=d[2], qualification=d[3], experience=d[4], fee=d[5], days=d[6], status=d[7],
                  email=d[8], existing=d[9], contact=rnd_phone(), username=d[8].split("@")[0].replace(".f", ".fernandes") if d[8] == "melissa.f@lifetag.com" else d[8].split("@")[0])
             for d in DOCTORS],
    staff=[dict(name=n, username=u, role=r) for n, u, r in EXTRA_STAFF],
    patients=[clean_patient(p) for p in P],
    beds=BEDS,
    admissions=ADM, appointments=APPT, vitals=VIT, reports=REP, treatments=TRT,
    bills=[dict(b, discount=b["discount"], covered=b["covered"]) for b in BILLS],
    insuranceProviders=[dict(name=n, contact=c) for n, c in PROVIDERS],
    policies=POLICIES, insuranceClaims=[{k: v for k, v in c.items() if k != "policy"} | {"policy": POLICIES.index(c["policy"])} for c in CLAIMS],
    schemes=[dict(name=s[0], description=s[1], eligibility=s[2], benefits=s[3], category=s[4]) for s in SCHEMES_NEW],
    enrollments=ENROLL,
    schemeClaims=[dict({k: v for k, v in c.items() if k != "enroll"}, enroll=ENROLL.index(c["enroll"]), pkg=PACKAGES.get(c["ep"], ("MD-GEN-001", "Medical management"))) for c in SCLAIMS],
    bloodBank=BLOODBANK, bloodRequests=BLOODREQ, feedback=FEEDBACK, staffActivity=ACTS, notifications=NOTIFS,
)
