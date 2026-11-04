#!/usr/bin/env bash
# ssh into the test VM: sshvm.sh '<command>'
exec ssh -i /d/pv-linux-test/id_test -p 2222 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o BatchMode=yes -o LogLevel=ERROR ubuntu@127.0.0.1 "$@"
