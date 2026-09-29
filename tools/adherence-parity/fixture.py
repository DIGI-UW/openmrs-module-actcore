"""Usage: ACT2_APP=<is4r-rhd-cdk>/resources/app python3 fixture.py ../../api/src/test/resources/adherence-parity.json

Writes adherence-parity.json: ACT 2.0's calculate_adherence_and_injection_date on its own test inputs
and on random histories, for the Java port's parity test."""
import ast, datetime as dt, json, pathlib, random, sys
import act2

TODAY = dt.date(2026, 9, 29)
calc = act2.load(TODAY)
TESTS = act2.APP / 'test_adherence.py'

def act2_test_inputs():
    """The (prescriptions, injections, oral) arguments of every call in test_adherence.py, with today pinned."""
    tree = ast.parse(TESTS.read_text())
    ns = {}
    class Pinned(dt.datetime):
        @classmethod
        def now(cls, tz=None):
            return cls(TODAY.year, TODAY.month, TODAY.day, 12, tzinfo=tz)
    ns.update({'datetime': Pinned, 'timezone': dt.timezone, 'timedelta': dt.timedelta})
    assigns = [n for n in tree.body if isinstance(n, ast.Assign) and all(isinstance(t, ast.Name) for t in n.targets)]
    exec(compile(ast.Module(body=assigns, type_ignores=[]), str(TESTS), 'exec'), ns)
    calls = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and getattr(node.func, 'id', '') == 'calculate_adherence_and_injection_date':
            calls.append(tuple(ns[a.id] for a in node.args))
    return calls

NAMES = ['Q14 day BPG', 'Q21 day BPG', 'Q28 day BPG', 'Oral penicillin V', 'Other']
DURATIONS = [None, '1 month', '3 months', '6 months', '12 months']

def random_inputs(rng):
    day = lambda: TODAY - dt.timedelta(days=rng.choice([rng.randint(-20, 400), rng.randint(0, 1200), rng.randint(0, 60)]))
    prescriptions = {day(): [{'name': rng.choice(NAMES)}] for _ in range(rng.randint(0, 4))}
    injections = {day(): {} for _ in range(rng.choice([0, rng.randint(1, 15)]))}
    oral = {day(): [{'adherence_estimate': rng.choice([rng.randint(0, 100) * 1.0, None, 'a']),
                     'new_prescription_duration': rng.choice(DURATIONS)}] for _ in range(rng.choice([0, rng.randint(1, 4)]))}
    return prescriptions, injections, oral

def encode(prescriptions, injections, oral):
    adherence, due = calc(prescriptions, injections, oral)
    return {
        'prescriptions': [[d.isoformat(), v[0]['name']] for d, v in sorted(prescriptions.items())],
        'injections': sorted(d.isoformat() for d in injections),
        'oral': [[d.isoformat(), v[0].get('adherence_estimate') if isinstance(v[0].get('adherence_estimate'), (int, float)) else None,
                  v[0].get('new_prescription_duration')] for d, v in sorted(oral.items())],
        'adherence': adherence,
        'nextDue': due.isoformat() if due else None,
    }

rng = random.Random(26)
def boundary_inputs():
    """Prescriptions on either side of the 365-day cutoff, alone and followed by a later one."""
    for offset in (364, 365, 366):
        for name in ('Q21 day BPG', 'Oral penicillin V'):
            start = TODAY - dt.timedelta(days=offset)
            for later in (None, 10, 364):
                prescriptions = {start: [{'name': name}]}
                if later:
                    prescriptions[TODAY - dt.timedelta(days=later)] = [{'name': 'Q28 day BPG'}]
                yield prescriptions, {start + dt.timedelta(days=30): {}}, {start: [{'adherence_estimate': 70.0}]}

cases = ([encode(*c) for c in act2_test_inputs()] + [encode(*c) for c in boundary_inputs()]
         + [encode(*random_inputs(rng)) for _ in range(400)])
out = pathlib.Path(sys.argv[1])
out.write_text(json.dumps({'today': TODAY.isoformat(), 'source': 'is4r-rhd-cdk adherence_utils.calculate_adherence_and_injection_date',
                           'cases': cases}, indent=1))
print(len(cases), 'cases;', sum(c['adherence'] is not None for c in cases), 'with an adherence;',
      sum(c['nextDue'] is not None for c in cases), 'with a due date')
