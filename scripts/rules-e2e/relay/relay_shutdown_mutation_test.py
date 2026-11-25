#!/usr/bin/env python3
"""Single-point in-memory mutations and corresponding focused tests."""
import pathlib, sys, types, unittest
import relay_shutdown_test as tests
source=(pathlib.Path(__file__).parent/'https_relay.py').read_text()
mutations={
 'bounds':("math.isfinite(v) and v > 0", "True", 'test_positive_finite_bounds'),
 'short':("if not chunk: raise ValueError('short body')", "if not chunk: break", 'test_short_body_rejected'),
 'body-deadline':("body_deadline-time.monotonic()", "self.args.timeout", 'test_slow_upload_total_deadline'),
 'once':("if self.watchdog_started: return", "if False: return", 'test_watchdog_only_starts_once'),
}
name=sys.argv[1]; mode=sys.argv[2]; old,new,test=mutations[name]
if source.count(old)!=1: raise SystemExit('mutation anchor is not unique')
if mode=='mutant': source=source.replace(old,new,1)
module=types.ModuleType('relay_mutant'); exec(compile(source,'https_relay.py','exec'),module.__dict__); tests.m=module
suite=unittest.TestSuite([tests.ShutdownTests(test)])
ok=unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful()
if mode=='mutant': sys.exit(0 if not ok else 1)
sys.exit(0 if ok else 1)
