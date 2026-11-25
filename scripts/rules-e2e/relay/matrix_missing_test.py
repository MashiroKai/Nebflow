#!/usr/bin/env python3
import ast, importlib.util, io, os, pathlib, sys, tempfile, threading, types, unittest
from unittest.mock import patch
import relay_concurrency_test as base
ROOT=pathlib.Path(__file__).parent
source=(ROOT/'https_relay.py').read_text(); rule=sys.argv[1]; mutate=sys.argv[2]=='mutant'
changes={
 'R2':("target=self.routes.get(route)","target=next(iter(self.routes.values()),None)"),
 'R3':("if host not in allowed:","if False:"),
 'R4':("p.add_argument('--timeout',type=float,default=120)","p.add_argument('--extra',type=float,default=120)"),
 'R5':("p.add_argument('--timeout',type=float,default=120)","p.add_argument('--test-override'); p.add_argument('--timeout',type=float,default=120)"),
 'R6':("1 <= args.limit <= 25","1 <= args.limit <= 26"),
 'R8':("self.stopping and self.inflight==0 and not self.stop_fired","self.stopping and not self.stop_fired"),
}
old,new=changes[rule]; assert source.count(old)==1
if mutate: source=source.replace(old,new,1)
module=types.ModuleType('matrix_relay'); exec(compile(source,'https_relay.py','exec'),module.__dict__); base.m=module
print('RULE',rule,'MODE',sys.argv[2],flush=True)
if mutate: print('MUTATION:',old,'=>',new,flush=True)
class RuleTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(dir=ROOT); self.addCleanup(self.tmp.cleanup); self.path=pathlib.Path(self.tmp.name)/'ledger'; self.env=patch.dict(os.environ,{'FAKE_KEY':'fake'}); self.env.start(); self.addCleanup(self.env.stop)
 def mk(self,host='example.com',allowed=None,limit=3):
  a=base.args(self.path,limit); a.route=['one,'+host+',/v1,FAKE_KEY']; a.allow_host=allowed or ['example.com']; return a
 def test_r2_unknown_path_does_not_forward(self):
  r=module.Relay(self.mk(),dialer=lambda *a:self.fail('unexpected dial')); h=base.H('trace'); h.path='/unknown/chat'; status=[]; h.send_error=lambda n,*a:status.append(n); r.handle(h); self.assertEqual(status,[403]); self.assertEqual(r.count,0); r.ledger.close(); r.owner.close()
 def test_r3_independent_exact_host(self):
  with self.assertRaisesRegex(ValueError,'route host not allowed'): module.Relay(self.mk(host='example.com.evil'))
 def test_r4_cli_exact_parameters(self):
  tree=ast.parse(source); actual={n.args[0].value for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr=='add_argument' and n.args and isinstance(n.args[0],ast.Constant)}; self.assertEqual(actual,{'--route','--allow-host','--port','--ledger','--limit','--seconds','--timeout'})
 def test_r5_no_test_parameters(self):
  tree=ast.parse(source); names=[n.args[0].value for n in ast.walk(tree) if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and n.func.attr=='add_argument' and n.args and isinstance(n.args[0],ast.Constant)]; self.assertFalse([x for x in names if x.startswith('--test-')])
 def test_r6_reject_26(self):
  with self.assertRaisesRegex(ValueError,'invalid bounds'): module.Relay(self.mk(limit=26))
 def test_r8_no_stop_while_inflight(self):
  r=module.Relay(self.mk()); r.stopping=True; r.inflight=1
  with r.condition: fired=r.maybe_stop()
  self.assertFalse(fired); self.assertFalse(r.stop_fired); r.ledger.close(); r.owner.close()
name={'R2':'test_r2_unknown_path_does_not_forward','R3':'test_r3_independent_exact_host','R4':'test_r4_cli_exact_parameters','R5':'test_r5_no_test_parameters','R6':'test_r6_reject_26','R8':'test_r8_no_stop_while_inflight'}[rule]
result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromName('RuleTests.'+name,sys.modules[__name__]))
sys.exit(0 if (len(result.failures)+len(result.errors)==1 if mutate else result.wasSuccessful()) else 1)
