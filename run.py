#!/usr/bin/env python3
"""
run.py — single-instance runner for the PWCF-SAR scheduler.

Usage (mirrors Task 6 benchmark commands):
    python run.py --n 8  --K 3  --density 0.3  --seed 1
    python run.py --n 50 --K 8  --density 0.25 --seed 10
    python run.py --n 200 --K 5 --density 0.60 --seed 21 --brute-force

The script:
  1. Generates a random instance using the reference generator (Section 5).
  2. Serialises it to a temporary JSON file.
  3. Invokes the compiled Java fat-JAR.
  4. Pretty-prints the result.

Build the JAR first with:
    mvn package -q
"""
import argparse
import json
import os
import subprocess
import sys
import time
import random
import tempfile

# ── Reference generator (Section 5 — DO NOT MODIFY) ─────────────────────────

def generate_instance(n, K, d=4, conflict_density=0.3, seed=42):
    """ Generate a random MSME Credit Pipeline Scheduling instance. """
    random.seed(seed)
    tasks = [f'T{i}' for i in range(n)]
    conflicts = [(i,j) for i in range(n) for j in range(i+1,n)
                 if random.random() < conflict_density]
    cap = [32, 128, 8, 6.0]  # CPU, RAM, GPU, Network
    resources = [[random.uniform(1, cap[d]//(n//K+1))
                  for d in range(4)] for _ in range(n)]
    capacities = [cap[:] for _ in range(K)]
    windows = [(lo := random.randint(0,K-2),
                random.randint(lo+1, K-1)) for _ in range(n)]
    weights = [random.uniform(1, 10) for _ in range(n)]
    return dict(tasks=tasks, conflicts=conflicts,
                resources=resources, capacities=capacities,
                windows=windows, weights=weights, K=K)

# ── Runner ───────────────────────────────────────────────────────────────────

def find_jar():
    """Locate the fat-JAR produced by 'mvn package'."""
    here = os.path.dirname(os.path.abspath(__file__))
    jar  = os.path.join(here, 'target',
                        'scheduler-1.0-SNAPSHOT-jar-with-dependencies.jar')
    if not os.path.exists(jar):
        print(f"ERROR: JAR not found at:\n  {jar}")
        print("Please run:  mvn package -q")
        sys.exit(1)
    return jar


def run_scheduler(instance_path, jar, brute_force=False):
    """Call Java scheduler, return parsed JSON result."""
    cmd = ['java', '-jar', jar, '--input', instance_path]
    if brute_force:
        cmd.append('--brute-force')

    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        print("ERROR: Java process exited with code", proc.returncode)
        print(proc.stderr)
        sys.exit(1)

    # The JAR prints only the JSON object to stdout
    return json.loads(proc.stdout)


def main():
    parser = argparse.ArgumentParser(
        description='MSME Credit Pipeline Scheduler — single-instance runner')
    parser.add_argument('--n',       type=int,   required=True,
                        help='Number of tasks')
    parser.add_argument('--K',       type=int,   required=True,
                        help='Number of processing slots')
    parser.add_argument('--density', type=float, default=0.3,
                        help='Conflict graph edge density (default 0.3)')
    parser.add_argument('--seed',    type=int,   default=42,
                        help='Random seed for instance generator (default 42)')
    parser.add_argument('--brute-force', action='store_true',
                        help='Force optimal brute-force search (use only for n ≤ 14)')
    parser.add_argument('--save-instance', type=str, default=None,
                        help='Optional path to save the generated instance JSON')
    args = parser.parse_args()

    # ── Generate instance ────────────────────────────────────────────────────
    print(f"Generating: n={args.n}  K={args.K}  density={args.density}  seed={args.seed}")
    inst = generate_instance(args.n, args.K, conflict_density=args.density, seed=args.seed)

    n_edges = len(inst['conflicts'])
    n_possible = args.n * (args.n - 1) // 2
    actual_density = n_edges / n_possible if n_possible > 0 else 0
    print(f"Conflicts:  {n_edges} edges  (actual density = {actual_density:.3f})")

    # Write to temp file (or user-specified path)
    if args.save_instance:
        inst_path = args.save_instance
        with open(inst_path, 'w') as fh:
            json.dump(inst, fh, indent=2)
        print(f"Instance saved → {inst_path}")
    else:
        fd, inst_path = tempfile.mkstemp(suffix='.json', prefix='scoreme_inst_')
        with os.fdopen(fd, 'w') as fh:
            json.dump(inst, fh)

    # ── Run Java scheduler ───────────────────────────────────────────────────
    jar = find_jar()
    use_bf = args.brute_force or args.n <= 14
    mode   = 'BruteForce (optimal)' if use_bf else 'PWCF-SAR (heuristic)'
    print(f"Algorithm:  {mode}")

    t0 = time.time()
    result = run_scheduler(inst_path, jar, brute_force=use_bf)
    wall_s = time.time() - t0

    # ── Print result ─────────────────────────────────────────────────────────
    print()
    print("=" * 50)
    if result['feasible']:
        print(f"  feasible     : True")
        print(f"  penalty      : {result['penalty']:.6f}")
        print(f"  runtime_ms   : {result['runtime_ms']} ms  (wall: {wall_s*1000:.1f} ms)")
        asgn = result['assignment']
        print(f"  assignment   : {json.dumps(asgn, sort_keys=True)}")
    else:
        print(f"  feasible     : False")
        print(f"  violation    : {result['violation_reason']}")
        print(f"  runtime_ms   : {result['runtime_ms']} ms")
    print("=" * 50)

    # Clean up temp file
    if not args.save_instance and os.path.exists(inst_path):
        os.remove(inst_path)

    return result


if __name__ == '__main__':
    main()
