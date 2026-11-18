#!/usr/bin/env python3
"""Run isolated Google Authenticator checks without changing the app's users or database."""
import argparse
from pathlib import Path
import re
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--maven', help='Path to the Maven executable')
parser.add_argument('--repo', help='Optional writable Maven cache directory')
parser.add_argument('--offline', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
maven = args.maven or shutil.which('mvn')
if not maven:
    wrappers = sorted((Path.home()/'.m2/wrapper/dists').glob('apache-maven-*/**/bin/mvn'))
    maven = str(wrappers[-1]) if wrappers else None
if not maven:
    sys.exit('Maven was not found. Install Maven or pass --maven /path/to/bin/mvn.')
command = [maven, '-X', '-DskipTests', 'test-compile']
if args.repo:
    command.append('-Dmaven.repo.local='+str(Path(args.repo).resolve()))
if args.offline:
    command.append('-o')
compiled = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
if compiled.returncode:
    print('\n'.join(line for line in compiled.stdout.splitlines() if '[ERROR]' in line))
    sys.exit(compiled.returncode)
paths = re.findall(r'\(f\) testPath = \[(.*?)\]', compiled.stdout)
if not paths:
    sys.exit('Could not read the resolved test classpath from Maven compiler output.')
classpath = paths[-1].replace(', ', ';' if sys.platform == 'win32' else ':')
result = subprocess.run(['java', '-cp', classpath, 'com.intellidesk.AuthenticatorChecks'], cwd=root)
sys.exit(result.returncode)
