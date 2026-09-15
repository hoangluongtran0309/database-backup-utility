# ADR-016: Restore streams the dump into the client

**Status**: Accepted
**Date**: 2026-09
**Supersedes**: in [ADR-007](007-restore-applies-a-dump-and-asks-first.md), how the bytes get there — the temporary file — and the consequence that a restore needs free space for the decompressed dump

## Context

A restore decompressed the whole archive to a file beside it, then pointed the
client's stdin at that file ([ADR-007](007-restore-applies-a-dump-and-asks-first.md)).
A logical dump compresses well, so that file is many times the archive: a 2 GB
archive could need 15 GB free. The restore then fails for lack of disk at the
moment it is needed most — after an incident, on a host that may be filling up
for the same reason.

ADR-007 chose the file to avoid a deadlock: a Java thread writing the child's
stdin while the child blocks writing output nobody is reading.

## Decision

The archive is decompressed **as it is fed** to the client's stdin, by a thread
of its own, and nothing is written to disk.

Before the client starts, the archive is read once to the end, its
decompressed bytes discarded. A truncated or corrupt archive fails there, with
the target untouched.

`ProcessRunner.runFeeding` replaces `runWithInput`. Every pipe — stdin, stdout,
stderr — gets a dedicated thread, not one from the common pool.

## Rationale

**The deadlock needs a shared thread, and there is none.** It happens when one
thread both writes the child's input and is responsible for draining its
output, or when output is not drained at all. `ProcessRunner` drains stdout and
stderr on threads of their own, and always has. With the input on a third, a
blocked write only ever waits for the child to read, and the child is never
kept from reading by output nobody takes. `ProcessRunnerTest` feeds five
megabytes through `cat`, which echoes every byte, so both directions are far
past a pipe buffer at once.

**Dedicated threads, not the common pool.** Each pipe thread blocks for as long
as its child runs. The common pool has one worker fewer than the host has cores:
two jobs, the default, at three pipes each, would leave a pipe on a small host
waiting for a worker that never frees up, and its child blocked until the
timeout. That was already possible with two pipes per job; the third made it
likely.

**Why read the archive twice.** The temporary file did one more thing than
ADR-007 said: it decompressed everything before the client saw a byte, so a bad
archive never reached the target. Streaming alone would apply every statement up
to the bad bytes, then fail — a partial restore from a file that was never
going to work. Reading it once first keeps that guarantee for the cost of a read
and the CPU to inflate it, and no disk. Checksums ([ADR-013](013-a-checksum-for-every-artifact.md))
already catch a changed file, but not one saved before checksums were kept, or
one damaged between the check and the restore.

**Why the child is killed before its stdin is closed.** If the source fails
anyway, partway — a disk error on the second read — closing the child's stdin
would give it a clean end of input, and the client would exit 0 having applied
half a dump. Killing it first, and waiting until it is gone, means a failed
read is always a failed restore.

## Consequences

- A restore needs no free space beyond the archive itself.
- The archive is decompressed twice. For a large dump that is CPU time before the
  client starts, still less than writing and reading back a decompressed copy.
- A write to the child that fails means the child stopped reading — `--batch`
  stops at the first error — and its exit code and stderr are reported as
  before, not a broken pipe.
- The source stream belongs to the caller, as the sink does in `runStreaming`;
  `ProcessRunner` never closes it.
- A read failure after the check still leaves whatever statements ran before it
  applied, as any restore that fails partway does
  ([ADR-007](007-restore-applies-a-dump-and-asks-first.md)); the message says the
  input could not be read.
