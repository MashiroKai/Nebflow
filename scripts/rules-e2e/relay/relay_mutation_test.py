#!/usr/bin/env python3
import importlib.util, pathlib, sys, types, unittest, tempfile
import relay_concurrency_test as base
import relay_ledger_test as tests
source=(pathlib.Path(__file__).parent/'https_relay.py').read_text()
mutations={
 'crash_intent':("def sidecar(self,obj):\n  data=", "def sidecar(self,obj):\n  if str(obj.get('record_id','')).startswith('torn-main-'): return\n  data=",'RecoveryTests.test_repair_crash_before_and_after_truncate'),
 'timeout':('self.sidecar(obj); return','return','test_lock_timeout_writes_sidecar'),
 'recovery':('self.recover_sidecar()','pass # mutated recovery','test_restart_merges_sidecar_and_enforces_limit'),
 'abort':('writer.join(self.abort_timeout)','writer.join()','test_stalled_abort_writer_exits'),
 'reservation':('self.stopping=True; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()','self.stopping=False; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()','test_total_reservation_failure_stops_without_dial'),
 'recovery_lock':("if left<=0: raise ValueError('ledger recovery lock timeout')","if left<=0: break",'RecoveryTests.test_recovery_lock_timeout_child'),
 'torn':("records.append({'seq':maxseq+1,'status':'reserved','record_id':'torn-'+__import__('hashlib').sha256(line).hexdigest()})","pass # lose torn record",'RecoveryTests.test_torn_sidecar_reserves_conservatively'),
 'chain':('self.recover_sidecar()','pass # lose recovery','RecoveryTests.test_force_restart_limit_end_to_end'),
 'failure':('self.stopping=True; self.count-=1; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()','self.stopping=True; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()','RecoveryTests.test_total_failure_restart_no_dial'),
 'main_tail':("seq=max([x['seq'] for x in records]+[0])+1","raise ValueError('torn ledger rejected')",'RecoveryTests.test_torn_main_conservative_and_middle_corruption_rejected'),
 'merge_id':("if obj['record_id'] not in ids:","if True:",'RecoveryTests.test_merge_crash_idempotent_restart'),
 'identity':("identity=entry.get('record_id')","identity=entry.get('seq')",'RecoveryTests.test_torn_main_and_sidecar_same_seq_both_count'),
 'uncertain':("self.stopping=True; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()","self.stopping=True; self.count-=1; self.inflight-=1; self.active.pop(seq,None); fire=self.maybe_stop()",'RecoveryTests.test_fsync_uncertain_failure_fails_closed'),
 'inode':("if os.fstat(ledger.fileno()).st_ino != os.stat(self.args.ledger).st_ino:","if False:",'RecoveryTests.test_replaced_inode_waiter_uses_sidecar'),
}
name=sys.argv[1]; old,new,test=mutations[name]
assert source.count(old)==1
mutated=source.replace(old,new,1); module=types.ModuleType('mutant'); exec(compile(mutated,'https_relay.py','exec'),module.__dict__); tests.m=module; base.m=module
print('MUTATION:',old,'=>',new,flush=True)
with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
 path=pathlib.Path(tmp)/'mutant.py'; path.write_text(mutated); tests.RELAY_PATH=path
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromName(test if '.' in test else 'LedgerTests.'+test,tests))
sys.exit(0 if len(result.failures)+len(result.errors)==1 else 1)
