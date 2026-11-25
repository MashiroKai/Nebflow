#!/usr/bin/env python3
"""Single-point in-memory mutations and corresponding focused tests."""
import pathlib, sys, types, unittest
import relay_shutdown_test as tests
source=(pathlib.Path(__file__).parent/'https_relay.py').read_text()
mutations={
 'N1':('if self.sealed: return None','if False: return None','test_seal_prevents_main_and_sidecar_append'),
 'N2':('self.append_inflight[key]=seq if key is not None and seq is not None else tag','pass # no inflight registration','test_hard_stop_records_pending_abort'),
 'N3':('   return self._event_unlocked(obj)\n  finally: leave()','   with self.append_lock: return self._event_unlocked(obj)\n  finally: leave()','test_admission_lock_not_held_during_io'),
 'N4':('  finally: leave()\n def _event_unlocked','  finally: pass\n def _event_unlocked','test_writer_leaves_after_seal'),
 'N5':("if not self.sealed: raise RuntimeError('abort before sealing')","if False: raise RuntimeError('abort before sealing')",'test_exit_abort_requires_seal_and_only_writes_sidecar'),
 'N6':("if not self.sidecar(synthetic): raise RecoveryAborted('recovery intent not durable')","self.sidecar(synthetic)",'test_repair_sealed_before_intent_preserves_tail'),
 'N7':("len(run_keys & final)","len(final)",'test_summary_excludes_prior_run'),
 'bounds':("math.isfinite(v) and v > 0", "True", 'test_positive_finite_bounds'),
 'short':("if not chunk: raise ValueError('short body')", "if not chunk: break", 'test_short_body_rejected'),
 'body-deadline':("body_deadline-time.monotonic()", "self.args.timeout", 'test_slow_upload_total_deadline'),
 'seal':("if self.sealed: return False", "if False: return False", 'test_seal_prevents_main_and_sidecar_append'),
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
