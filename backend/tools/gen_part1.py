# -*- coding: utf-8 -*-
"""
LifeTag HMS - large demo dataset generator.

Writes ../src/main/resources/seed/large-dataset.json, which LargeDatasetSeeder.java loads on
first start of a disposable demo database. Everything is deterministic (fixed random seed) and all
dates are stored as day OFFSETS from the day the seeder runs (0 = today, -30 = a month ago), so
the data always looks current whenever you start the app.

Re-generate:   python3 tools/generate_seed_dataset.py
"""
import json, random, datetime as dt, os, sys
from decimal import Decimal, ROUND_HALF_UP

R = random.Random(20261005)
REF = dt.date(2026, 10, 5)  # only used to avoid booking on Sundays; offsets are relative to seed day

def weekday(off):
    return (REF + dt.timedelta(days=off)).weekday()

def clamp(v, lo, hi):
    return max(lo, min(hi, v))

def pick(seq):
    return seq[R.randrange(len(seq))]

def wchoice(pairs):
    tot = sum(w for _, w in pairs)
    x = R.random() * tot
    for v, w in pairs:
        x -= w
        if x <= 0:
            return v
    return pairs[-1][0]

def chance(p):
    return R.random() < p

def rnd_phone():
    return "9" + "".join(str(R.randrange(10)) for _ in range(9))

# ----------------------------------------------------------------------------- doctors
# name, specialization, department, qualification, experience, fee, days, status, email, existing
DOCTORS = [
    ("Dr. Sarah Johnson", "Cardiology", "Cardiology", "MD, DM Cardiology", 12, 800, "Mon, Wed, Fri", "On Duty", "sarah.johnson@lifetag.com", True),
    ("Dr. Arvind Iyer", "Pediatrics", "Pediatrics", "MD Pediatrics", 8, 600, "Tue, Thu, Sat", "On Duty", "arvind.iyer@lifetag.com", True),
    ("Dr. Melissa Fernandes", "Orthopedics", "Orthopedics", "MS Orthopedics", 15, 900, "Mon, Tue, Fri", "On Leave", "melissa.f@lifetag.com", True),
    ("Dr. Rohan Kulkarni", "Cardiology", "Cardiology", "MD, DM Cardiology", 14, 1000, "Tue, Thu, Sat", "On Duty", "rohan.kulkarni@lifetag.com", False),
    ("Dr. Nandini Rao", "Pediatrics", "Pediatrics", "MD, DCH Pediatrics", 10, 600, "Mon, Wed, Fri", "On Duty", "nandini.rao@lifetag.com", False),
    ("Dr. Vikram Singh", "Orthopedics", "Orthopedics", "MS, MCh Orthopedics", 18, 900, "Mon, Wed, Thu", "On Duty", "vikram.singh@lifetag.com", False),
    ("Dr. Pooja Deshpande", "General Medicine", "General Medicine", "MD Internal Medicine", 9, 500, "Mon, Tue, Wed, Thu, Fri", "On Duty", "pooja.deshpande@lifetag.com", False),
    ("Dr. Imran Shaikh", "General Medicine", "General Medicine", "MD Internal Medicine", 15, 600, "Mon, Wed, Fri, Sat", "On Duty", "imran.shaikh@lifetag.com", False),
    ("Dr. Kavita Menon", "Endocrinology", "Endocrinology", "MD, DM Endocrinology", 11, 900, "Tue, Thu", "On Duty", "kavita.menon@lifetag.com", False),
    ("Dr. Suresh Bhat", "Neurology", "Neurology", "MD, DM Neurology", 20, 1200, "Mon, Thu, Sat", "On Duty", "suresh.bhat@lifetag.com", False),
    ("Dr. Anjali Verma", "Neurology", "Neurology", "MD, DM Neurology", 7, 900, "Tue, Wed, Fri", "On Duty", "anjali.verma@lifetag.com", False),
    ("Dr. Farhan Qureshi", "Pulmonology", "Pulmonology", "MD Pulmonary Medicine", 13, 800, "Mon, Tue, Thu", "On Duty", "farhan.qureshi@lifetag.com", False),
    ("Dr. Lakshmi Narayan", "Nephrology", "Nephrology", "MD, DM Nephrology", 16, 1000, "Wed, Fri, Sat", "On Duty", "lakshmi.narayan@lifetag.com", False),
    ("Dr. Rahul Patil", "Gastroenterology", "Gastroenterology", "MD, DM Gastroenterology", 12, 900, "Mon, Wed, Fri", "On Duty", "rahul.patil@lifetag.com", False),
    ("Dr. Meera Joshi", "Obstetrics & Gynecology", "Obstetrics & Gynecology", "MS, DNB Obstetrics", 17, 800, "Mon, Tue, Thu, Sat", "On Duty", "meera.joshi@lifetag.com", False),
    ("Dr. Shalini Kapoor", "Obstetrics & Gynecology", "Obstetrics & Gynecology", "MD, DGO", 8, 700, "Tue, Wed, Fri", "Off Duty", "shalini.kapoor@lifetag.com", False),
    ("Dr. Deepak Choudhary", "Emergency Medicine", "Emergency Medicine", "MD Emergency Medicine", 10, 600, "Mon, Tue, Wed, Thu, Fri, Sat", "On Duty", "deepak.choudhary@lifetag.com", False),
    ("Dr. Sneha Pawar", "Dermatology", "Dermatology", "MD Dermatology", 9, 700, "Tue, Thu, Sat", "On Duty", "sneha.pawar@lifetag.com", False),
    ("Dr. Aditya Rao", "ENT", "ENT", "MS ENT", 11, 700, "Mon, Wed, Fri", "On Duty", "aditya.rao@lifetag.com", False),
    ("Dr. Sanjay Mishra", "General Surgery", "General Surgery", "MS, MCh General Surgery", 19, 1000, "Mon, Tue, Thu, Fri", "On Duty", "sanjay.mishra@lifetag.com", False),
]
DOC_BY_DEPT = {}
for _i, _d in enumerate(DOCTORS):
    DOC_BY_DEPT.setdefault(_d[2], []).append(_i)
DOC_ON_LEAVE = {2}   # Dr. Melissa Fernandes: not booked in the last 45 days / future

def doctor_for(dept, off):
    """Pick a doctor of a department who could plausibly see patients at day offset `off`."""
    cands = [i for i in DOC_BY_DEPT[dept] if not (i in DOC_ON_LEAVE and off > -45)]
    if not cands:
        cands = DOC_BY_DEPT[dept]
    return pick(cands)

def dname(i):
    return DOCTORS[i][0]

# ----------------------------------------------------------------------------- people
MALE = "Rohit Amit Suresh Rajesh Vikas Prakash Sandeep Ganesh Mahesh Nitin Sachin Anil Deepak Manoj Pradeep Ashok Santosh Yogesh Kiran Harish Nikhil Omkar Sagar Tushar Swapnil Rahul Ajay Vishal Abhijit Dinesh Faisal Imtiyaz Salman Irfan Arvind Mohan Ravi Sunil Naveen Pankaj Ramesh Vijay Sanjay Aarav Vivaan Aditya Arnav Reyansh Atharv Shreyas Yash Kabir Ishaan Dev Rudra Neel Parth Tejas Bhushan Chetan Girish Hemant".split()
FEMALE = "Priya Sunita Anita Meena Kavita Sneha Pooja Neha Swati Rekha Asha Shweta Vaishali Madhuri Jyoti Manisha Savita Rupali Sarika Archana Ayesha Fatima Zoya Nisha Divya Aditi Ananya Diya Isha Kiara Myra Saanvi Tanvi Riya Shruti Pallavi Smita Lata Usha Seema Geeta Radha Sheetal Komal Gauri Rutuja Prerana Mansi Payal Trupti Alka Bhavna Chhaya Deepa Hema Indu Kalyani Laxmi Nalini".split()
SURNAMES = ["Patil", "Kulkarni", "Deshmukh", "Joshi", "Jadhav", "Pawar", "Shinde", "More", "Kamble", "Gaikwad", "Chavan", "Salunkhe", "Bhosale", "Mane", "Shaikh", "Khan", "Pathan", "Sayyed", "Naik", "Rane", "Sawant", "Desai", "Thakur", "Sharma", "Verma", "Gupta", "Singh", "Iyer", "Nair", "Menon", "Reddy", "Rao", "Nambiar", "Pillai", "Kapoor", "Mehta", "Shah", "Patel", "Bhatt", "Dixit", "Apte", "Phadke", "Gokhale", "Kelkar", "Karve", "Bapat", "Ghule", "Waghmare", "Lokhande", "Mulla", "Momin", "Attar", "Inamdar", "D'Souza", "Fernandes", "Pereira", "Dias", "Rodrigues", "Chaudhari", "Mishra", "Pandey", "Tiwari", "Yadav"]
STREETS = ["Station Road", "MG Road", "Lake View", "Market Yard", "Shivaji Nagar", "Vishrambag", "Ganpati Peth", "Tilak Chowk", "College Road", "Gandhi Chowk", "Model Colony", "Kasba Peth", "Hanuman Nagar", "Raviwar Peth", "Azad Nagar", "Sangli Road"]
CITIES = [("Sangli", 30), ("Miraj", 14), ("Kolhapur", 12), ("Pune", 14), ("Satara", 6), ("Ichalkaranji", 5), ("Islampur", 4), ("Mumbai", 5), ("Karad", 4), ("Solapur", 3), ("Belgaum", 3)]
BLOOD = [("O+", 36), ("A+", 22), ("B+", 24), ("AB+", 6), ("O-", 4), ("A-", 3), ("B-", 3), ("AB-", 2)]
ALLERGIES = ["Penicillin", "Sulfa drugs", "Peanuts", "Dust mites", "Latex", "NSAIDs (ibuprofen)", "Shellfish", "Pollen", "Aspirin", "Eggs"]
NURSES = ["Nurse Sunita D.", "Nurse Rekha P.", "Nurse Joseph F.", "Nurse Anuja K.", "Nurse Mangal S.", "Nurse Priti R.", "Nurse Teresa D.", "Nurse Kiran B."]
RADIOLOGISTS = ["Dr. P. Kulkarni (Radiologist)", "Dr. A. Deshpande (Radiologist)", "Dr. N. Shetty (Radiologist)", "Dr. R. Bhide (Radiologist)"]
PATHOLOGISTS = ["Dr. S. Gokhale (Pathologist)", "Dr. M. Karnik (Pathologist)", "Dr. T. Almeida (Pathologist)"]
CARDIO_TECH = ["Dr. V. Rajan (Cardiac Lab)", "Dr. K. Menon (Cardiac Lab)"]

STAFF_ADMINS = [("Rajesh Mehta", "rajesh.mehta"), ("Anita Deshmukh", "anita.deshmukh"), ("Meenakshi Rao", "meenakshi.rao")]
STAFF_RECEPTION = [("Neha Kapoor", "neha.kapoor"), ("Suresh Pillai", "suresh.pillai"), ("Kavita Naidu", "kavita.naidu"),
                   ("Rohan Bhatia", "rohan.bhatia"), ("Sunita Rao", "sunita.rao"), ("Imran Ansari", "imran.ansari")]
EXTRA_STAFF = [("Meenakshi Rao", "meenakshi.rao", "ADMIN"), ("Kavita Naidu", "kavita.naidu", "RECEPTION"),
               ("Rohan Bhatia", "rohan.bhatia", "RECEPTION"), ("Sunita Rao", "sunita.rao", "RECEPTION"),
               ("Imran Ansari", "imran.ansari", "RECEPTION")]
