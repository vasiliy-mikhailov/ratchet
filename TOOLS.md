# Which tools an agent actually uses

`Tool` is three strings and ratchet does not ship any. That is the right split — the loop should not
decide what a caller's agent can do. But every caller then guesses the same list, and this is what the
guess should be, measured rather than reasoned.

The numbers below are 816 tool calls across six runs of one task on 2026-08-30: make ~21 Java
repositories compile by writing surrogate jars for `ru.nsd.*` artifacts that do not exist locally.
Real repositories, real Maven and Gradle, a 27B model behind an OpenAI-compatible endpoint.

## The measurement

| tool | calls | share | present in |
| --- | ---: | ---: | --- |
| `bash` | 582 | 71.3% | 6/6 |
| `write` | 108 | 13.2% | 5/6 |
| `edit` | 53 | 6.5% | 4/6 |
| `job_output` | 32 | 3.9% | 4/6 |
| `read` | 20 | 2.5% | 5/6 |
| `todo_write` | 11 | 1.3% | 6/6 |
| `create_goal` | 5 | 0.6% | 4/6 |
| `grep` | 3 | 0.4% | 2/6 |
| `job_kill` | 1 | 0.1% | 1/6 |
| `web_search` | 1 | 0.1% | 1/6 |

**Five tools carry 97%: `bash`, `write`, `edit`, `job_output`, `read`.** Everything below `read` is
rounding error. `grep` was called three times in six runs and `bash` can do it; `web_search` once.

## The one that is easy to leave out

`job_output` is 3.9% of calls and it gates everything. A Maven or Gradle build on a real corpus runs
for minutes. Without a way to start work in the background and collect it later, the agent either
blocks a synchronous tool past its timeout or does not run the build at all — and the build is the
only thing that tells it whether the surrogate it just wrote was right. The 32 calls look minor in
the table because each one stands for a build the agent was able to wait for.

`job_kill` was used once. Ship it anyway; it costs nothing and the one use was a build that hung.

## What `bash` is really doing

71% in one tool says less than it looks like. Broken down:

| | calls | |
| --- | ---: | --- |
| file inspection | 235 | 40% — `cat`, `ls`, `find`, `grep`, `sed`, `awk` |
| maven | 82 | 14% |
| network fetch | 74 | 13% — Maven Central, the GitLab API |
| python | 61 | 10% — inline scripts for generation and parsing |
| gradle | 49 | 8% |
| git | 35 | 6% |
| jdk tooling | 26 | 4% — `javac`, `javap`, `jar`, `unzip` |

Two of those rows are requirements on the environment rather than on the tool list, and an agent
without them cannot do this work no matter what tools it is handed:

**Two JDKs, separately addressable.** One repository family needs 21 and the other needs 11; the
agent sets `JAVA_HOME` per invocation. A single JDK is not a degraded version of this — it is a
failure.

**Network egress.** 13% of shell calls are fetches. The best move observed all day was pulling
`instancio-core` from Maven Central and running `javap` against the jar to read the real method
signatures before writing a surrogate against them. Cut the network and the agent is back to
inferring an API from compiler errors, which is how the weaker runs spent their time.

Also assumed: a writable `~/.m2`, `python3`, `unzip`, and disk for a full dependency tree.

## What I would advertise, on this corpus alone

    bash  write  edit  read  job_output  job_kill  todo_write

`todo_write` appears in 6/6 runs at 1.3% — cheap, and every run reached for it. `create_goal` is the
same shape. Skip `grep` and `web_search`: the first is `bash`, and the second went unused because
this task is answerable from the repositories and Maven Central.

**That `grep` line was wrong, and the next section is why.** It is left standing because it is what
this corpus says, and a measurement that gets edited after the fact stops being one.

## A second corpus, three orders of magnitude apart

Reported by a different consumer on 2026-08-31: **11,328 tool calls across 24 lanes** of a pipeline
that writes surrogate implementations for missing types.

| tool | calls | share | present in |
| --- | ---: | ---: | --- |
| `read_file` | 5,089 | 44.9% | 24/24 |
| `grep` | 1,797 | 15.9% | 24/24 |
| `write_file` | 1,151 | 10.2% | 8/24 |
| `list_dir` | 1,099 | 9.7% | 24/24 |
| `glob` | 988 | 8.7% | 24/24 |
| `edit_file` | 536 | 4.7% | 21/24 |
| `compile_run_tests_and_get_errors` | 281 | 2.5% | 24/24 |
| `signature` | 167 | 1.5% | 9/24 |
| `deps` | 149 | 1.3% | 24/24 |

`grep` is SECOND here and present in every lane. Against three calls in six runs above, that is
about three orders of magnitude — which is too far apart to be two samples of the same thing. It is
two different kinds of agent.

**The task is the difference.** A fake-writer's central question is *what shape must this missing
type be*, and the only answer is every use site of a name across a tree. `grep` IS that question;
`read` is what you do once it has answered. Together they are 61% of everything that pipeline does.
An agent navigating a repository it already understands does not look like this — it reads and it
runs, which is the corpus at the top of this file.

**So the omission was doubly wrong.** Not only is `grep` load-bearing for a whole class of work, the
reason given for leaving it out — *`bash` can do it* — holds only for a caller who takes `bash`. The
caller who refuses a shell, because every guard they have is enforced at the tool boundary, was
being told to search with the one tool they had already ruled out. Two defensible decisions
combining into an indefensible one.

`grep` and `glob` ship in 0.23.0, beside the file tools rather than beside `bash`.

**What did not ship, and why.** `signature` — read a library type's real members instead of
inventing them — is a good tool and its owner should keep it; it needs a JDK and a resolved
classpath, which is a build system's knowledge and not a library's. `compile_run_tests_and_get_errors`
and `deps` are the same shape. A tool that needs to know how the project builds belongs to whoever
knows that.

## What this does not establish

Six runs of one task family. `web_search` being unused here means this corpus did not need the open
web, not that the tool is useless. The share of `bash` would fall in a task with less shelling out.
And the counts come from runs that were not controlled against each other — different starting
state, different stopping points — so read the table as *which tools were reached for at all*, not as
a ranking anyone should tune against.
