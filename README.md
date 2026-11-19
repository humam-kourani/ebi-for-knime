# Ebi for KNIME

Run [Ebi](https://github.com/BPM-Research-Group/Ebi) process-mining commands as KNIME nodes, alongside [PM4KNIME](https://github.com/pm4knime/pm4knime). Nodes are generated from Ebi's command descriptions and expose compatible input and output ports.

## Install

Requires **KNIME Analytics Platform 5.12** and **PM4KNIME 3.0**. Native execution supports 64-bit Windows and Linux.

1. Open **Preferences → Install/Update → Available Software Sites**.
2. Click **Add**, enter `Ebi Update Site` and set its location to `https://humam-kourani.github.io/ebi-for-knime/`. Ensure the site is enabled.
3. Click **Apply and Close**.
4. Open **Menu → Install Extensions**, select **Ebi Process Mining for KNIME**, install, and restart KNIME.

For future releases, choose **Menu → Check for updates** and restart when prompted.

## Use

Search for **Ebi** in the KNIME node repository. Select compatible port types using the node's port controls. **Ebi document** ports preserve native formats; commands that export SVG also offer a KNIME image output.

## Releases

The [update site](https://humam-kourani.github.io/ebi-for-knime/) is the recommended installation and update source. [GitHub Releases](https://github.com/humam-kourani/ebi-for-knime/releases) also provide an update-site ZIP for offline installation.

The [build and release workflow](.github/workflows/build-release.yml) regenerates nodes from a validated Ebi release and runs automated tests. Tested releases are published to the update site using [the deployment workflow](.github/workflows/publish-update-site.yml).
