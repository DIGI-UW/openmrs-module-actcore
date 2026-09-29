"""Loads ACT 2.0's pure adherence functions, unchanged, from adherence_utils.py, with today pinned."""
import ast, datetime as dt, os, pathlib
# ACT2_APP is the resources/app directory of an is4r-rhd-cdk checkout.
APP = pathlib.Path(os.environ['ACT2_APP'])
SRC = APP / 'query_handlers' / 'adherence_utils.py'
NAMES = {'filter_dates', 'method_to_days', 'get_next_injection_date', 'get_next_oral_due_date',
         'calculate_adherence_and_injection_date'}

def load(today):
    class PinnedDatetime(dt.datetime):
        @classmethod
        def now(cls, tz=None):
            return cls(today.year, today.month, today.day, 12, tzinfo=tz)
    tree = ast.parse(SRC.read_text())
    tree.body = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in NAMES]
    ns = {'datetime': PinnedDatetime, 'timezone': dt.timezone, 'timedelta': dt.timedelta, 'date': dt.date}
    exec(compile(tree, str(SRC), 'exec'), ns)
    return ns['calculate_adherence_and_injection_date']
