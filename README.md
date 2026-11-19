# Ebi for KNIME

**Use Ebi's process mining algorithms directly in KNIME workflows.**

[Ebi](https://github.com/BPM-Research-Group/Ebi) is a process mining toolkit. This extension makes its supported commands available as KNIME nodes, so you can connect them to other KNIME and [PM4KNIME](https://github.com/pm4knime/pm4knime) nodes instead of running Ebi separately.

The nodes are **generated from Ebi's own command descriptions**. Each node provides suitable settings and input/output ports. Where a command supports different compatible types, you can choose the relevant port type in KNIME. The extension handles data conversion, calls Ebi, and returns the result to the workflow.

The build report published with each release records its generated nodes and supported input/output combinations. These numbers can change when Ebi changes.

## How the GitHub workflow works

The [build and release workflow](.github/workflows/build-release.yml) keeps the extension aligned with Ebi and produces an installable KNIME extension. The separate [deployment workflow](.github/workflows/publish-update-site.yml) publishes a tested release to the update site.

```text
Ebi GitHub Release
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
                  |
                  v
       Manual update-site publishing
```

### Where updates come from

The workflow checks the **prebuilt Ebi Java/JNI libraries** published in the [Ebi GitHub Releases](https://github.com/BPM-Research-Group/Ebi/releases):

- **New packaging:** `Ebi.jar` containing `libebi.so`, `ebi.dll`, and `src/surfaces/java/org/ebitools/Ebi.java`
- **Older packaging (still supported):** separate `libebi.so` and `ebi.dll`

It validates and compares the native binaries with the recorded hashes. When a new Ebi release changes, it **captures the new command metadata and documentation from that exact native version, then recreates all generated KNIME nodes, settings, ports, and registrations**. The workflow checks that the two binaries are compatible and runs tests before allowing a release. In `latest` mode, a newer upstream release with missing or invalid JNI files **fails the build**; the old pinned version is used automatically only when no newer upstream release exists. Use `pinned` explicitly to rebuild the locked version.

### When it runs

| Trigger | What it does | Creates a GitHub release? |
| --- | --- | --- |
| **Run build workflow** (manual) | Checks latest Ebi by default, or uses the pinned version you select | **Yes by default**, if tests pass; can be turned off |
| **Run deployment workflow** (manual) | Publishes an existing, tested GitHub release to the update site | No |

If any required check fails, **no new version is published**. The previous release stays available.

## What the workflow produces

Each successful build prepares:

| Output | Purpose |
| --- | --- |
| **`knime-ebi-update-site.zip`** | The installable KNIME extension, including the needed Ebi native libraries |
| **`REPORT.md` and `REPORT.json`** | A summary of generated nodes, supported combinations, Ebi changes and test results |
| **Checksums and build information** | Records which binaries and versions were used |

Build results and reports are available as **GitHub Actions artifacts**. When publishing is enabled and all required tests pass, the workflow also creates a **versioned GitHub Release** with the installer, build report and checksums. After testing, the deployment workflow can publish that release to the [update site](https://humam-kourani.github.io/ebi-for-knime/).

## Installing the extension

Use **KNIME Analytics Platform 5.12 or higher**. In KNIME, open **Preferences → Install/Update → Available Software Sites**, click **Add**, and enter `https://humam-kourani.github.io/ebi-for-knime/`. Make sure the site is enabled and click **Apply and Close**. Then choose **Menu → Install Extensions**, install **Ebi Process Mining for KNIME**, and restart KNIME.

For later releases, choose **Menu → Check for updates** and restart when prompted. The [GitHub Releases](https://github.com/humam-kourani/ebi-for-knime/releases) also provide a downloadable update-site ZIP.

The packaged native libraries support **64-bit Windows and Linux**.
