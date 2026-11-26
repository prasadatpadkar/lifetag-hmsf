#!/usr/bin/env python3
"""
Post-processes src/main/resources/seed/large-dataset.json so the seed follows the billing rules:

  * invoice "covered" / "cnote" are cleared - insurance / scheme cover is no longer typed into an invoice;
    it is derived at start-up from SANCTIONED claims only (Approved insurance claims / sanctioned scheme
    claims). Claims that are only submitted / under review therefore show as "awaiting sanction" and are
    not deducted from what the patient owes.
  * every claim keeps its own status and amount; non-approved claims carry no approved / sanctioned amount.

Run automatically at the end of generate_seed_dataset.py; safe to run repeatedly.
"""
import json, os, sys

here = os.path.dirname(os.path.abspath(__file__))
path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "..", "src", "main", "resources", "seed", "large-dataset.json")

with open(path, encoding="utf-8") as f:
    data = json.load(f)

cleared = 0
for b in data["bills"]:
    if b.get("covered"):
        cleared += 1
    b["covered"] = 0
    b["cnote"] = None

for c in data["insuranceClaims"]:
    c["sanctioned"] = c["amount"] if c["status"] == "Approved" else 0
for c in data["schemeClaims"]:
    c["sanctioned"] = c["amount"] if c["status"] == "Approved" else 0

with open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
print("cleared typed-in cover on %d invoices; cover now comes from sanctioned claims only" % cleared)
