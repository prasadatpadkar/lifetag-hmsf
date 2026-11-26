#!/usr/bin/env python3
"""Generates src/main/resources/seed/large-dataset.json (see gen docs at the top of part 1)."""
import os, json
here = os.path.dirname(os.path.abspath(__file__))
g = {}
for part in ["gen_part1.py", "gen_part2.py", "gen_part3.py", "gen_part4.py", "gen_part5.py", "gen_part6.py", "gen_part7.py"]:
    exec(compile(open(os.path.join(here, part), encoding="utf-8").read(), part, "exec"), g)
out = os.path.join(here, "..", "src", "main", "resources", "seed", "large-dataset.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(g["OUT"], f, ensure_ascii=False, separators=(",", ":"), default=float)
print("wrote", os.path.normpath(out), os.path.getsize(out) // 1024, "KB")
for k, v in g["OUT"].items():
    print("  %-18s %d" % (k, len(v)))

# Apply the billing rules (cover only from sanctioned claims) to the freshly generated file
import subprocess, sys
subprocess.check_call([sys.executable, os.path.join(here, "align_seed_with_billing_rules.py"), out])
