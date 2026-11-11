# Ebi for KNIME

**Use Ebi's process mining algorithms directly in KNIME workflows.**

[Ebi](https://leemans.ch/ebi/) is a process mining toolkit. This extension makes its supported commands available as KNIME nodes, so you can connect them to other KNIME and [PM4KNIME](https://github.com/pm4knime/pm4knime-core) nodes instead of running Ebi separately.

The nodes are **generated from Ebi's own command descriptions**. Each node provides suitable settings and input/output ports. Where a command supports different compatible types, you can choose the relevant port type in KNIME. The extension handles data conversion, calls Ebi, and returns the result to the workflow.

The working **1.1.1 baseline** generates **56 nodes** covering **439 supported input/output combinations**. These numbers can change when Ebi changes.

## How the GitHub workflow works

The single workflow, [`.github/workflows/ebi.yml`](.github/workflows/ebi.yml), keeps the extension aligned with Ebi and produces an installable KNIME extension.

```text
Ebi native libraries
       |
       v
Check for changes --> Read Ebi command metadata
       |                       |
       +-----------------------+
                  |
                  v
         Regenerate KNIME nodes
                  |
                  v
         Test on Linux + Windows
                  |
                  v
          Build KNIME update site
                  |
                  v
          Report + optional release
```

### Where updates come from

The workflow checks the **prebuilt Ebi Java/JNI libraries** published in the [RWTH Aachen Ebi repository](https://git.rwth-aachen.de/rwth-bpm/rustlibrary/-/tree/main/build/nightly/java):

- **Linux:** `libebi.so`
- **Windows:** `ebi.dll`

It compares the downloads with the recorded file hashes. When they change, it uses the new Ebi library to refresh the **command metadata and documentation**, then regenerates the KNIME node classes, settings, ports and registrations. The workflow checks that the two binaries are compatible and runs tests before allowing a release.

**It does not build Ebi from Rust source**, upgrade KNIME or PM4KNIME, or automatically add new Java conversion code for previously unsupported types.

### When it runs

| Trigger | What it does | Creates a GitHub release? |
| --- | --- | --- |
| **Run workflow** (manual) | Checks latest Ebi by default, or uses the pinned version you select | **Yes by default**, if tests pass; can be turned off |

If any required check fails, **no new version is published**. The previous release stays available.

## What the workflow produces

Each successful build prepares:

| Output | Purpose |
| --- | --- |
| **`knime-ebi-update-site.zip`** | The installable KNIME extension, including the needed Ebi native libraries |
| **`REPORT.md` and `REPORT.json`** | A compact summary of generated nodes, supported combinations, Ebi changes and test results |
| **Plug-in JAR** | The compiled extension, also included within the update-site ZIP |
| **Checksums and build information** | Records which binaries and versions were used |
| **`INSTALL.txt`** | Short installation instructions |

Build results and reports are available as **GitHub Actions artifacts**. When publishing is enabled and all required tests pass, the workflow also creates a **versioned GitHub Release** with the installer and reports.

## Installing the extension

Use **KNIME Analytics Platform 5.12** with **PM4KNIME 3.0**. Download `knime-ebi-update-site.zip` from the latest successful GitHub Release, then in KNIME choose **Help → Install New Software → Add → Archive**, select the ZIP, install the extension and restart KNIME.

The packaged native libraries support **64-bit Windows and Linux**. 