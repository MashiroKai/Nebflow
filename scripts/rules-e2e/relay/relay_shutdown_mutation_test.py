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
 'limit-stop':("if final: self.start_watchdog()", "if final: pass", 'test_limit_hard_stop_ledger_lock'),
 'premature-finalize':("if not done.wait(self.lock_timeout+self.abort_timeout): return False", "if not done.wait(self.lock_timeout+self.abort_timeout):\n   with self.rejection_lock: self.rejections[identity]='unrecorded'\n   return False", 'test_late_results_after_429'),
 'reject-order':("   self.rejection_event({'seq':seq,'status':'rejected-limit','method':'POST','host':host,'path':path,'dial':False,'time':time.time(),'rule_id':None,'trace_id':trace[0]})\n   h.send_error(429); return", "   h.send_error(429)\n   self.rejection_event({'seq':seq,'status':'rejected-limit','method':'POST','host':host,'path':path,'dial':False,'time':time.time(),'rule_id':None,'trace_id':trace[0]}); return", 'test_rejection_record_before_response'),
}
name=sys.argv[1]; mode=sys.argv[2]; old,new,test=mutations[name]
if source.count(old)!=1: raise SystemExit('mutation anchor is not unique')
if mode=='mutant': source=source.replace(old,new,1)
module=types.ModuleType('relay_mutant'); exec(compile(source,'https_relay.py','exec'),module.__dict__); tests.m=module
suite=unittest.TestSuite([tests.ShutdownTests(test)])
ok=unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful()
if mode=='mutant': sys.exit(0 if not ok else 1)
sys.exit(0 if ok else 1)
