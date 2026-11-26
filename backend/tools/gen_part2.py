# ----------------------------------------------------------------------------- lab + imaging reports
def fmt(v, dp):
    return str(int(round(v))) if dp == 0 else ("%.*f" % (dp, v))

def lab(name, unit, lo, hi, v, dp=1, rng=None):
    flag = "N"
    if v < lo:
        flag = "L"
    elif v > hi:
        flag = "H"
    if rng is None:
        rng = ("<%s" % fmt(hi, dp)) if lo == 0 else ("%s - %s" % (fmt(lo, dp), fmt(hi, dp)))
    return {"test": name, "value": fmt(v, dp), "unit": unit, "range": rng, "flag": flag}

def lab_txt(name, value, normal, unit=""):
    return {"test": name, "value": value, "unit": unit, "range": normal, "flag": "N" if value == normal else "H"}

def male(p):
    return p["gender"] == "Male"

def prog(ctx, scale=1.4):
    return min(1.0, ctx["t"] * scale)

INFECT = {"DENGUE", "TYPHOID", "PNEUMONIA", "FEVER_VIRAL", "UTI", "GASTRO", "APPENDICITIS", "ASTHMA_EXAC", "SINUSITIS"}

def lab_cbc(ctx):
    p, conds = ctx["p"], ctx["conds"]
    ac = ctx.get("acute")
    if "ANEMIA" in conds:
        hb = 9.0 + 2.6 * prog(ctx, 1.2) + R.gauss(0, 0.5)
    elif p["age"] < 12:
        hb = R.gauss(12.5, 0.7)
    else:
        hb = R.gauss(14.6, 0.9) if male(p) else R.gauss(12.9, 0.8)
    if ac == "DENGUE":
        hb += 1.0
    wbc = R.gauss(7200, 1400)
    if ac in INFECT and ac != "DENGUE":
        wbc = R.gauss(14500, 2800)
    if ac == "DENGUE":
        wbc = R.gauss(3300, 600)
    plt = R.gauss(265000, 50000)
    if ac == "DENGUE":
        plt = clamp(R.gauss(52000, 22000), 12000, 120000)
    esr = R.gauss(10, 5) if ac not in INFECT else R.gauss(42, 14)
    hb_lo, hb_hi = (13.0, 17.0) if male(p) else (12.0, 15.5)
    if p["age"] < 12:
        hb_lo, hb_hi = 11.0, 14.5
    return [lab("Hemoglobin", "g/dL", hb_lo, hb_hi, clamp(hb, 5.5, 18)),
            lab("Total WBC count", "/uL", 4000, 11000, clamp(wbc, 1800, 28000), 0),
            lab("Platelet count", "/uL", 150000, 410000, clamp(plt, 8000, 480000), 0),
            lab("ESR", "mm/hr", 0, 20, clamp(esr, 2, 90), 0)]

def lab_hba1c(ctx):
    v = (9.2 - 2.1 * prog(ctx, 1.3) + R.gauss(0, 0.45)) if "T2DM" in ctx["conds"] else R.gauss(5.3, 0.3)
    return [lab("HbA1c", "%", 4.0, 5.6, clamp(v, 4.2, 14.0)),
            lab("Estimated average glucose", "mg/dL", 68, 114, clamp(28.7 * v - 46.7, 70, 380), 0)]

def lab_fbs(ctx):
    dm = "T2DM" in ctx["conds"]
    f = (165 - 38 * prog(ctx, 1.3) + R.gauss(0, 18)) if dm else R.gauss(90, 8)
    pp = f + (R.gauss(60, 20) if dm else R.gauss(30, 12))
    return [lab("Fasting blood glucose", "mg/dL", 70, 100, clamp(f, 55, 380), 0),
            lab("Post-prandial glucose (2h)", "mg/dL", 70, 140, clamp(pp, 70, 460), 0)]

def lab_lipid(ctx):
    conds, p = ctx["conds"], ctx["p"]
    risk = ("HTN" in conds) + ("T2DM" in conds) + 2 * ("CAD" in conds)
    tc = R.gauss(182, 24) + 14 * risk
    hdl = R.gauss(44 if male(p) else 52, 6)
    tg = R.gauss(135, 38) + 32 * ("T2DM" in conds) + 12 * risk
    ldl = tc - hdl - tg / 5.0
    if "CAD" in conds:
        ldl *= (1 - 0.35 * prog(ctx, 1.5))
        tc = ldl + hdl + tg / 5.0
    return [lab("Total cholesterol", "mg/dL", 0, 200, clamp(tc, 110, 340), 0),
            lab("LDL cholesterol", "mg/dL", 0, 100, clamp(ldl, 40, 250), 0),
            lab("HDL cholesterol", "mg/dL", 40 if male(p) else 50, 100, clamp(hdl, 25, 90), 0),
            lab("Triglycerides", "mg/dL", 0, 150, clamp(tg, 50, 480), 0)]

def lab_kft(ctx):
    conds, p = ctx["conds"], ctx["p"]
    if "CKD" in conds:
        cr = 1.7 + 0.9 * ctx["t"] + R.gauss(0, 0.2)
        urea, k, na = R.gauss(62, 14), R.gauss(5.1, 0.4), R.gauss(137, 2)
    else:
        cr = (R.gauss(0.95, 0.14) if male(p) else R.gauss(0.76, 0.12)) + (0.15 if ("T2DM" in conds and ctx["t"] > 0.6) else 0)
        urea, k, na = R.gauss(27, 6), R.gauss(4.2, 0.35), R.gauss(140, 2)
    ua = R.gauss(5.3, 1.1)
    lo, hi = (0.7, 1.3) if male(p) else (0.5, 1.1)
    return [lab("Serum creatinine", "mg/dL", lo, hi, clamp(cr, 0.3, 6.5), 2),
            lab("Blood urea", "mg/dL", 15, 45, clamp(urea, 8, 140), 0),
            lab("Uric acid", "mg/dL", 3.5, 7.2, clamp(ua, 2.0, 11), 1),
            lab("Sodium", "mmol/L", 135, 145, clamp(na, 125, 150), 0),
            lab("Potassium", "mmol/L", 3.5, 5.0, clamp(k, 2.8, 6.8), 1)]

def lab_lft(ctx):
    fatty = chance(0.25)
    return [lab("Total bilirubin", "mg/dL", 0.2, 1.2, clamp(R.gauss(0.7, 0.2), 0.1, 3), 1),
            lab("SGOT (AST)", "U/L", 5, 40, clamp(R.gauss(48 if fatty else 27, 8), 10, 160), 0),
            lab("SGPT (ALT)", "U/L", 7, 56, clamp(R.gauss(66 if fatty else 30, 10), 8, 190), 0),
            lab("Alkaline phosphatase", "U/L", 44, 147, clamp(R.gauss(95, 22), 40, 260), 0),
            lab("Serum albumin", "g/dL", 3.5, 5.0, clamp(R.gauss(4.2, 0.3), 2.5, 5.2), 1)]

def lab_thyroid(ctx):
    if "THYROID" in ctx["conds"]:
        tsh = 13.0 - 9.5 * prog(ctx, 1.4) + R.gauss(0, 1.1)
        t4 = 4.3 + 3.8 * prog(ctx, 1.4) + R.gauss(0, 0.5)
    else:
        tsh, t4 = R.gauss(2.1, 0.8), R.gauss(8.0, 1.2)
    return [lab("TSH", "uIU/mL", 0.4, 4.0, clamp(tsh, 0.05, 40), 2),
            lab("Free T4", "ug/dL", 5.0, 12.0, clamp(t4, 2.0, 16), 1),
            lab("Total T3", "ng/dL", 80, 200, clamp(R.gauss(118, 22), 40, 260), 0)]

def lab_urine(ctx):
    conds = ctx["conds"]
    prot = "Trace" if (("CKD" in conds) or ("T2DM" in conds and chance(0.3))) else "Nil"
    if "CKD" in conds and chance(0.5):
        prot = "+"
    glu = "+" if ("T2DM" in conds and ctx["t"] < 0.5 and chance(0.6)) else "Nil"
    pus = clamp(R.gauss(2.5, 1.2), 0, 7)
    if ctx.get("acute") == "UTI":
        pus = clamp(R.gauss(26, 8), 10, 60)
    return [lab_txt("Urine protein", prot, "Nil"), lab_txt("Urine glucose", glu, "Nil"),
            lab("Pus cells", "/hpf", 0, 5, pus, 0, "0 - 5"),
            lab("RBC", "/hpf", 0, 2, clamp(R.gauss(0.8, 0.8), 0, 12), 0, "0 - 2")]

def lab_vitd(ctx):
    v = R.gauss(19, 7) if (ctx["p"]["age"] > 45 or "OA_KNEE" in ctx["conds"]) else R.gauss(32, 9)
    return [lab("Vitamin D (25-OH)", "ng/mL", 30, 100, clamp(v, 5, 80), 1)]

def lab_iron(ctx):
    if "ANEMIA" in ctx["conds"] or "ANC" in ctx["conds"]:
        fe = 38 + 45 * prog(ctx, 1.2) + R.gauss(0, 9)
        fer = 8 + 30 * prog(ctx, 1.2) + R.gauss(0, 4)
    else:
        fe, fer = R.gauss(95, 22), R.gauss(75, 30)
    return [lab("Serum iron", "ug/dL", 60, 170, clamp(fe, 15, 220), 0),
            lab("Serum ferritin", "ng/mL", 15, 300, clamp(fer, 3, 400), 0)]

def lab_crp(ctx):
    v = R.gauss(46, 22) if ctx.get("acute") in INFECT else R.gauss(2.2, 1.5)
    return [lab("C-reactive protein", "mg/L", 0, 6, clamp(v, 0.2, 180), 1)]

def lab_trop(ctx):
    mi = ctx.get("acute") in ("MI", "ANGINA_OBS")
    if ctx.get("acute") == "MI":
        v = clamp(R.gauss(3.4, 1.8), 0.6, 15)
    else:
        v = R.gauss(0.012, 0.005) if not mi else R.gauss(0.02, 0.008)
    return [lab("Troponin I", "ng/mL", 0, 0.04, clamp(v, 0.001, 20), 3)]

def lab_dengue(ctx):
    return [{"test": "Dengue NS1 antigen", "value": "Positive", "unit": "", "range": "Negative", "flag": "H"},
            {"test": "Dengue IgM antibody", "value": "Positive" if chance(0.5) else "Negative", "unit": "", "range": "Negative", "flag": "N"}]

def lab_widal(ctx):
    r = [{"test": "Salmonella typhi O titre", "value": "1:160", "unit": "", "range": "< 1:80", "flag": "H"},
         {"test": "Salmonella typhi H titre", "value": "1:320", "unit": "", "range": "< 1:80", "flag": "H"}]
    return r

# imaging / cardiac / special: key -> spec
# spec: type, category, title, cost range, who, lab, normal(findings, impression), abnormal: {cond: (findings, impression)}
def S(type_, cat, title, cost, who, labn, normal, abn):
    return dict(type=type_, category=cat, title=title, cost=cost, who=who, lab=labn, normal=normal, abn=abn)

IMG = {
 "MRI_BRAIN": S("MRI", "Radiology", "MRI Brain (plain + contrast)", (7000, 9500), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("No acute infarct, haemorrhage or space-occupying lesion. Ventricles, sulci and basal cisterns are normal. No abnormal contrast enhancement.", "Normal MRI brain study."),
    {"STROKE": ("Acute infarct in the left MCA territory with restricted diffusion (DWI bright, ADC dark) involving the left frontal and parietal lobes. Mild mass effect, no haemorrhagic transformation. Old small lacunar infarcts in bilateral corona radiata.", "Acute left MCA territory infarct. Advise vascular imaging and stroke work-up."),
     "MIGRAINE": ("Few non-specific T2/FLAIR hyperintensities in the subcortical white matter of both frontal lobes, commonly seen in migraine. No mass, no infarct, no vascular malformation.", "No structural cause for headache. Non-specific white-matter changes."),
     "HTN": ("Scattered periventricular and subcortical T2/FLAIR hyperintensities (Fazekas grade 1) suggestive of chronic small-vessel ischaemic change. No acute infarct.", "Mild chronic small-vessel ischaemic changes.")}),
 "CT_HEAD": S("CT Scan", "Radiology", "CT Head (plain)", (3500, 5000), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("No intracranial haemorrhage or mass effect. Grey-white differentiation preserved. Midline structures central.", "Normal CT head."),
    {"STROKE": ("Hypodensity in the left MCA territory with loss of grey-white differentiation and effacement of sulci. No intracranial haemorrhage.", "Early left MCA territory infarct; MRI advised.")}),
 "MRI_LSPINE": S("MRI", "Radiology", "MRI Lumbosacral Spine", (6500, 8500), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Lumbar vertebral alignment and marrow signal normal. Intervertebral discs well hydrated. Spinal canal and neural foramina are patent.", "Normal MRI lumbosacral spine."),
    {"BACKPAIN": ("Loss of lumbar lordosis. L4-L5 posterior disc bulge with left paracentral protrusion causing mild canal stenosis and compression of the traversing left L5 nerve root. Mild disc desiccation at L5-S1.", "L4-L5 disc protrusion with left L5 radiculopathy. Clinical correlation and physiotherapy advised; surgical opinion if neurological deficit.")}),
 "MRI_KNEE": S("MRI", "Radiology", "MRI Knee Joint", (6000, 7500), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Menisci, cruciate and collateral ligaments are intact. Articular cartilage is preserved. No joint effusion.", "Normal MRI knee."),
    {"OA_KNEE": ("Grade III-IV cartilage loss of the medial femorotibial compartment with subchondral marrow oedema. Degenerative tear of the posterior horn of the medial meniscus. Marginal osteophytes and moderate joint effusion.", "Advanced medial compartment osteoarthritis with degenerative meniscal tear.")}),
 "XRAY_KNEE": S("X-Ray", "Radiology", "X-Ray Knee AP / Lateral (weight-bearing)", (500, 800), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Joint spaces are maintained. No fracture, effusion or bony lesion.", "Normal knee radiograph."),
    {"OA_KNEE": ("Narrowing of the medial joint space with marginal osteophytes and subchondral sclerosis. Kellgren-Lawrence grade III. Varus alignment.", "Osteoarthritis knee, Kellgren-Lawrence grade III.")}),
 "XRAY_LSPINE": S("X-Ray", "Radiology", "X-Ray Lumbosacral Spine AP / Lateral", (500, 800), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Vertebral heights and alignment maintained. Disc spaces preserved.", "Normal lumbosacral spine radiograph."),
    {"BACKPAIN": ("Straightening of the lumbar curvature. Reduced L4-L5 and L5-S1 disc height with marginal osteophytes.", "Early lumbar spondylosis with reduced disc height.")}),
 "XRAY_WRIST": S("X-Ray", "Radiology", "X-Ray Wrist / Forearm AP & Lateral", (450, 700), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("No fracture or dislocation.", "No bony injury."),
    {"FRACTURE": ("Transverse fracture of the distal radius with dorsal angulation and about 4 mm displacement. Ulnar styloid intact.", "Displaced distal radius fracture (Colles type).")}),
 "XRAY_CHEST": S("X-Ray", "Radiology", "X-Ray Chest PA View", (400, 650), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Lung fields are clear. Cardiac size normal. Costophrenic angles are sharp. No pleural effusion.", "Normal chest radiograph."),
    {"PNEUMONIA": ("Right lower zone patchy consolidation with air bronchograms. Right costophrenic angle blunted with small effusion.", "Right lower lobe pneumonia with small parapneumonic effusion."),
     "COPD": ("Hyperinflated lungs with flattened diaphragm, increased retrosternal space and attenuated peripheral vascular markings.", "Changes of chronic obstructive airway disease."),
     "ASTHMA": ("Mild bronchial wall thickening with perihilar peribronchial cuffing. No consolidation.", "Findings in keeping with reactive airway disease."),
     "ASTHMA_EXAC": ("Hyperinflated lungs with bilateral perihilar peribronchial thickening. No consolidation or pneumothorax.", "Acute asthma exacerbation; no pneumonia."),
     "CAD": ("Mild cardiomegaly (CTR 0.55). Lung fields clear.", "Mild cardiomegaly.")}),
 "USG_ABD": S("Ultrasound", "Radiology", "Ultrasound Abdomen & Pelvis", (900, 1600), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Liver normal in size and echotexture. Gall bladder, pancreas, spleen and both kidneys are normal. No free fluid.", "Normal ultrasound study of the abdomen."),
    {"GALLSTONES": ("Gall bladder contains multiple calculi, the largest 11 mm, with mild wall thickening (4 mm) and no pericholecystic fluid. CBD not dilated.", "Cholelithiasis with early chronic cholecystitis."),
     "APPENDICITIS": ("Non-compressible blind-ending tubular structure in the right iliac fossa measuring 9 mm with periappendiceal fat stranding and a small amount of free fluid.", "Acute appendicitis."),
     "HERNIA": ("Defect of about 18 mm in the right inguinal region with bowel loop herniating on Valsalva; reducible, no signs of obstruction.", "Right inguinal hernia, reducible."),
     "GERD": ("Grade I fatty liver. Other abdominal organs normal.", "Grade I fatty liver."),
     "T2DM": ("Grade I fatty liver. Kidneys of normal size and echotexture.", "Grade I fatty liver.")}),
 "USG_KUB": S("Ultrasound", "Radiology", "Ultrasound KUB (kidneys, ureters, bladder)", (800, 1300), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Both kidneys are normal in size, shape and echotexture. No calculus or hydronephrosis. Bladder normal.", "Normal KUB ultrasound."),
    {"CKD": ("Both kidneys are reduced in size (right 8.4 cm, left 8.1 cm) with increased cortical echogenicity and poor corticomedullary differentiation. No hydronephrosis.", "Chronic medical renal disease."),
     "KIDNEY_STONE": ("Calculus 8 mm at the right pelviureteric junction with mild hydronephrosis. Left kidney normal.", "Right PUJ calculus with mild hydronephrosis.")}),
 "USG_OB": S("Ultrasound", "Radiology", "Obstetric Ultrasound", (1000, 1800), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("Single live intrauterine foetus in cephalic presentation. Foetal biometry corresponds to gestational age. Liquor adequate. Placenta fundo-anterior, grade I.", "Single live intrauterine pregnancy, growth appropriate for gestational age."),
    {}),
 "CT_ABD": S("CT Scan", "Radiology", "CT Abdomen & Pelvis (contrast)", (6500, 9000), RADIOLOGISTS, "LifeTag Imaging Centre",
    ("No abnormality detected in the abdominal viscera.", "Normal CT abdomen."),
    {"APPENDICITIS": ("Dilated appendix (10 mm) with wall enhancement and periappendiceal fat stranding; an appendicolith at the base. No perforation or abscess.", "Acute uncomplicated appendicitis.")}),
 "ECG": S("ECG", "Cardiac", "12-lead ECG", (300, 450), CARDIO_TECH, "LifeTag Cardiac Lab",
    ("Normal sinus rhythm, rate 76/min. Normal axis, PR and QRS intervals. No ST-T changes.", "Normal ECG."),
    {"MI": ("Sinus tachycardia. ST elevation of 3 mm in leads V1-V4 with reciprocal depression in II, III, aVF.", "Acute anterior wall myocardial infarction."),
     "ANGINA_OBS": ("Sinus rhythm. ST depression of 1.5 mm with T-wave inversion in V4-V6.", "Ischaemic ST-T changes."),
     "CAD": ("Sinus rhythm. T-wave inversion in leads V4-V6, no acute ST elevation. Old Q waves in leads V1-V3.", "Changes of ischaemic heart disease."),
     "HTN": ("Sinus rhythm. Voltage criteria of left ventricular hypertrophy (Sokolow-Lyon 39 mm) with secondary ST-T changes.", "Left ventricular hypertrophy with strain pattern.")}),
 "ECHO": S("2D Echo", "Cardiac", "2D Echocardiography with Doppler", (1800, 2600), CARDIO_TECH, "LifeTag Cardiac Lab",
    ("Normal chamber sizes. LVEF 62%. No regional wall motion abnormality. Valves normal. No pericardial effusion.", "Normal biventricular function, LVEF 62%."),
    {"MI": ("Hypokinesia of the anterior wall and apex. LVEF 38%. Mild mitral regurgitation. No LV thrombus.", "Anterior wall hypokinesia with moderate LV systolic dysfunction (LVEF 38%)."),
     "CAD": ("Hypokinetic basal-mid anterior septum. LVEF 46%. Grade I diastolic dysfunction. Mild mitral regurgitation.", "Regional wall motion abnormality with mild LV systolic dysfunction (LVEF 46%)."),
     "HTN": ("Concentric left ventricular hypertrophy (IVS 13 mm). LVEF 60%. Grade I diastolic dysfunction. Left atrium mildly dilated.", "Concentric LVH with grade I diastolic dysfunction; preserved systolic function."),
     "ANGINA_OBS": ("Normal chamber dimensions. LVEF 58%. No regional wall motion abnormality at rest.", "Preserved LV function at rest.")}),
 "TMT": S("TMT", "Cardiac", "Treadmill Stress Test (TMT)", (2000, 2800), CARDIO_TECH, "LifeTag Cardiac Lab",
    ("Bruce protocol, 9 min 30 s achieved, 10.1 METs, target heart rate reached. No ST changes, no arrhythmia, no symptoms.", "TMT negative for inducible ischaemia."),
    {"CAD": ("Bruce protocol, 6 min achieved. Horizontal ST depression of 2 mm in V4-V6 at peak exercise with chest discomfort, recovering by 5 min.", "TMT positive for inducible myocardial ischaemia."),
     "ANGINA_OBS": ("Bruce protocol, 7 min achieved. 1 mm ST depression in V5-V6 at 85% target heart rate, resolving in recovery.", "Borderline positive TMT; angiography advised.")}),
 "ANGIO": S("Coronary Angiography", "Cardiac", "Coronary Angiogram", (22000, 28000), CARDIO_TECH, "LifeTag Cath Lab",
    ("Normal epicardial coronary arteries.", "Normal coronary angiogram."),
    {"MI": ("Proximal LAD 95% stenosis with TIMI-1 flow. RCA and LCx show mild luminal irregularities.", "Single-vessel disease (proximal LAD); primary PTCA with drug-eluting stent done."),
     "CAD": ("Proximal LAD 70% and mid RCA 80% stenosis. LCx non-obstructive.", "Double-vessel coronary artery disease.")}),
 "PFT": S("Spirometry (PFT)", "Special Tests", "Pulmonary Function Test (spirometry)", (1000, 1500), CARDIO_TECH, "LifeTag Pulmonary Lab",
    ("FEV1 96% predicted, FVC 98% predicted, FEV1/FVC 82%. No obstruction or restriction.", "Normal spirometry."),
    {"ASTHMA": ("FEV1 74% predicted, FEV1/FVC 68%. Significant bronchodilator reversibility (FEV1 improved by 17%).", "Mild-moderate obstructive pattern with significant reversibility, consistent with asthma."),
     "COPD": ("FEV1 52% predicted, FEV1/FVC 58%. Poor bronchodilator reversibility (6%).", "Moderate irreversible airflow obstruction (GOLD 2)."),
     "ASTHMA_EXAC": ("FEV1 58% predicted, FEV1/FVC 63%, partial reversibility.", "Obstructive pattern during exacerbation.")}),
 "EEG": S("EEG", "Special Tests", "Electroencephalogram (EEG)", (1500, 2200), CARDIO_TECH, "LifeTag Neurophysiology Lab",
    ("Normal background alpha rhythm of 10 Hz. No epileptiform discharges during hyperventilation or photic stimulation.", "Normal awake EEG."),
    {}),
 "ENDO": S("Upper GI Endoscopy", "Special Tests", "Upper GI Endoscopy", (3500, 5500), RADIOLOGISTS, "LifeTag Endoscopy Suite",
    ("Oesophagus, stomach and duodenum appear normal.", "Normal upper GI endoscopy."),
    {"GERD": ("Grade B reflux oesophagitis at the lower oesophagus. Antral erythema with mild gastritis. Rapid urease test positive.", "Reflux oesophagitis (LA grade B) with H. pylori-positive antral gastritis.")}),
}

LABS = {
 "CBC": ("Laboratory", "Complete Blood Count (CBC)", "Blood Test", (350, 550), lab_cbc),
 "HBA1C": ("Laboratory", "Glycosylated Haemoglobin (HbA1c)", "Blood Test", (500, 700), lab_hba1c),
 "FBS": ("Laboratory", "Blood Sugar Fasting & Post-prandial", "Blood Test", (200, 320), lab_fbs),
 "LIPID": ("Laboratory", "Lipid Profile", "Blood Test", (600, 900), lab_lipid),
 "KFT": ("Laboratory", "Kidney Function Test (KFT)", "Blood Test", (600, 950), lab_kft),
 "LFT": ("Laboratory", "Liver Function Test (LFT)", "Blood Test", (600, 950), lab_lft),
 "THYROID": ("Laboratory", "Thyroid Profile (TSH, T3, T4)", "Blood Test", (550, 850), lab_thyroid),
 "URINE": ("Laboratory", "Urine Routine & Microscopy", "Urine Test", (150, 250), lab_urine),
 "VITD": ("Laboratory", "Vitamin D (25-OH)", "Blood Test", (1000, 1500), lab_vitd),
 "IRON": ("Laboratory", "Iron Studies (Serum iron, Ferritin)", "Blood Test", (700, 1100), lab_iron),
 "CRP": ("Laboratory", "C-Reactive Protein (CRP)", "Blood Test", (450, 700), lab_crp),
 "TROP": ("Laboratory", "Troponin I (quantitative)", "Blood Test", (900, 1300), lab_trop),
 "DENGUE": ("Laboratory", "Dengue NS1 Antigen & IgM", "Blood Test", (900, 1400), lab_dengue),
 "WIDAL": ("Laboratory", "Widal Test (Typhoid)", "Blood Test", (250, 400), lab_widal),
}

def round_to(v, step):
    return int(round(v / float(step)) * step)

def make_report(key, ctx, off, doctor_idx):
    """Builds one diagnostic report dict for the given report key in the patient's current context."""
    r = {"d": doctor_idx, "off": off, "billed": False}
    if key in LABS:
        cat, title, typ, cost, fn = LABS[key]
        results = fn(ctx)
        flags = [x["flag"] for x in results]
        abnormal = any(f != "N" for f in flags)
        crit = False
        for x in results:
            try:
                v = float(x["value"])
            except ValueError:
                continue
            if (x["test"] == "Hemoglobin" and v < 7.0) or (x["test"] == "Platelet count" and v < 20000) or \
               (x["test"] == "Potassium" and v > 6.0) or (x["test"] == "Serum creatinine" and v > 4.0) or \
               (x["test"] == "Fasting blood glucose" and v > 350) or (x["test"] == "Troponin I" and v > 1.0):
                crit = True
        bad = [x for x in results if x["flag"] != "N"]
        if bad:
            findings = "Abnormal values: " + "; ".join("%s %s %s (%s)" % (x["test"], x["value"], x["unit"], "high" if x["flag"] == "H" else "low") for x in bad[:6]) + "."
            impression = "Result outside reference range; clinical correlation advised."
        else:
            findings = "All parameters are within the biological reference range."
            impression = "Normal study."
        r.update(category=cat, type=typ, title=title, findings=findings, impression=impression,
                 resultStatus="Critical" if crit else ("Abnormal" if abnormal else "Normal"),
                 performedBy=pick(PATHOLOGISTS), labName="LifeTag Central Laboratory", results=results,
                 cost=round_to(R.uniform(*cost), 10))
        return r
    spec = IMG[key]
    conds = set(ctx["conds"])
    if ctx.get("acute"):
        conds.add(ctx["acute"])
    chosen = None
    for c in spec["abn"]:
        if c in conds:
            chosen = c
            break
    if chosen is None and spec["abn"] and key == "USG_OB":
        chosen = None
    if chosen:
        findings, impression = spec["abn"][chosen]
        status = "Critical" if (chosen in ("MI", "STROKE") and key in ("ECG", "MRI_BRAIN", "CT_HEAD", "ANGIO")) else "Abnormal"
    else:
        findings, impression = spec["normal"]
        status = "Normal"
    if key == "USG_OB":
        wk = ctx.get("ga_weeks", 24)
        findings = findings.replace("gestational age", "gestational age of about %d weeks" % wk)
    r.update(category=spec["category"], type=spec["type"], title=spec["title"], findings=findings,
             impression=impression, resultStatus=status, performedBy=pick(spec["who"]), labName=spec["lab"],
             results=None, cost=round_to(R.uniform(*spec["cost"]), 50 if spec["cost"][0] > 1500 else 10))
    return r
