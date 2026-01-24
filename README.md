# Hammerhead Karoo extension for controlling sports cameras

This is an extension to control sports action cameras using Hammerhead Karoo cycling computer.

[![Build](https://github.com/michalchecinski/karoo-camera-control/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/michalchecinski/karoo-camera-control/actions/workflows/ci.yml) [![Latest Release](https://img.shields.io/github/v/release/michalchecinski/karoo-camera-control?label=Latest%20Release)](https://github.com/michalchecinski/karoo-camera-control/releases/latest/) [![GitHub Downloads](https://img.shields.io/github/downloads/michalchecinski/karoo-camera-control/app-release.apk)](https://github.com/michalchecinski/karoo-camera-control/releases)

## Supported devices

For now only GoPro cameras are supported.

### GoPro

Up-to date [supported GoPro cameras list can be found here](https://gopro.github.io/OpenGoPro/#compatibility).

> [!NOTE]
> **Note on OpenGoPro SDK Integration:** Currently, this project integrates with GoPro cameras using BLE commands adapted from the official OpenGoPro Kotlin tutorial. The dedicated OpenGoPro Kotlin SDK (more information available at [https://gopro.github.io/OpenGoPro/kotlin_sdk/](https://gopro.github.io/OpenGoPro/kotlin_sdk/)) is not yet officially released as a distributable artifact (e.g., Maven package). Once the OpenGoPro Kotlin SDK becomes publicly available, the project will be migrated to utilize it for a more robust and streamlined integration.

## Credits

- Uses [karoo-ext](https://github.com/hammerheadnav/karoo-ext) (Apache2.0-licensed)
- Uses [Material Design Icons](https://github.com/google/material-design-icons)  (Apache2.0-licensed)

_This product and/or service is not affiliated with, endorsed by or in any way associated with GoPro Inc. or its products and services. GoPro, HERO, and their respective logos are trademarks or registered trademarks of GoPro, Inc._

_"Hammerhead", "Karoo", "Karoo SDK", "Karoo 2", "Karoo Extensions" and "karoo-ext" may be copyright and/or trademark and/or property of Hammerhead Navigation Inc or a parent or subsidiary company._
