#!/usr/bin/env python3
"""Fail closed: lifecycle fixtures must never detach the restart controller."""
raise SystemExit('Unexpected daemonize in isolated lifecycle fixture')
