#!/usr/bin/env python3
"""Static release routing contract; requires PyYAML, does not execute CI.

Scenarios: release routing and channel pointers; no main writeback.
"""
import re
from pathlib import Path

import yaml

root = Path(__file__).resolve().parents[1]
ci_text = (root / '.gitlab-ci.yml').read_text()
ci = yaml.safe_load(ci_text)
full_rule = ci['.rules-full']['rules'][1]['if']
release_rule = ci['release']['rules'][0]['if']


def matches(rule, branch):
    pattern = re.search(r'=~ /(.*)/$', rule).group(1).replace(r'\/', '/')
    return bool(re.fullmatch(pattern, branch))


for branch, full, release in [
    ('main', True, False), ('release/beta', True, True),
    ('release/stable', True, True), ('beta', False, False),
    ('release', False, False), ('release/other', False, False),
    ('feature/release/beta', False, False),
]:
    assert matches(full_rule, branch) == full, branch
    assert matches(release_rule, branch) == release, branch

script = '\n'.join(ci['release']['script'])
assert '"$CI_COMMIT_BRANCH" = "release/beta"' in script
assert 'release/beta)   POINTER="latest-beta-version.txt"' in script
assert 'release/stable) POINTER="latest-version.txt"' in script
assert '/repository/commits' not in script

# BaseLoader retains the GitHub Actions 'on' key as a string.
for name, branch in [('release.yml', 'release/beta'), ('auto-release.yml', 'release/stable')]:
    path = root / '.github/workflows' / name
    text = path.read_text()
    workflow = yaml.load(text, Loader=yaml.BaseLoader)
    assert workflow['on']['push']['branches'] == [branch]
    assert 'git push origin "refs/tags/' in text
    assert 'Sync version to main' not in text
    assert 'contents/VERSION?ref=main' not in text
print('PASS: release routing, channel pointers, tags and no main writeback')
