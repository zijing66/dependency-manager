<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Dependency Manager Changelog

[中文更新日志](CHANGELOG-CN.md)

## [1.1.1]

### Added

- Scanning Progress Display
- Added manual conda installation path selection and support for pip's conda mode with persistent configuration

### Fixed

- Fixed pip cache path extraction exception

## [1.1.0]

### Added

- Support for NPM package management
  - Cache cleanup for npm packages
  - Prerelease version detection (alpha, beta, rc, dev, etc.)
  - Package naming pattern detection
- Support for PIP package management
  - Cache cleanup for Python packages
  - Prerelease version detection according to PEP 440
  - Package naming pattern detection for wheel and sdist packages
  - Support for Python virtual environments (venv)
  - Support for Conda environments
  - Support for Pipenv environments

### Fixed

- Improved package information extraction logic to ensure correct display of versions and match types
- Optimized display logic to ensure only valid package data is shown
- Fixed Maven and Gradle package display logic to maintain dependency information consistency

## [1.0.5]

### Fixed

- Fixed some issues

## [1.0.4]

### Improved

- Optimize performance

## [1.0.3]

### Added
- Add `Match Type` display in field presentation

### Improved
- Optimize lookup efficiency

### Fixed
- Fix Gradle package lookup exceptions

## [1.0.2]

### Added
- Add double-click to open explorer feature in dependency cleanup tool
  - Double-click package name to open local storage location
  - Support Windows/macOS/Linux
- Add configuration service initialization error handling

### Improved
- Refactor dependency cleanup tool UI layout
  - Redesign progress bar and status label layout
  - Optimize component initialization sequence
- Enhance configuration service error handling

### Fixed
- Fix UI scaling issues on high-DPI displays
- Fix null pointer exception during config initialization


## [1.0.1]

### Added

- Support Gradle package management
- Support switching between Maven and Gradle
- Optimize UI styling

### Fixed

- Fixed some issues

## [1.0.0]

### Added

- Dependency analysis automation
- Maven local repository cleanup
- Interactive preview interface
- Multi-threaded progress monitoring
- Support deleting failed Maven downloads  
- Support deleting SNAPSHOT packages and specified groupId/artifactId 
- Compatibility optimization and search performance optimization

### Fixed

- Fixed compatibility issues with IntelliJ IDEA 2021.3
- Fixed ContentFactory.getInstance() method not found error
- Fixed kotlin.enums package not found error
- Fixed JVM signature clash in DependencyType enum

### Improved

- Changed package version separator from ":" to "@" for NPM and PIP dependencies
- Enhanced package information extraction for better display in UI
- Fixed scoped package name parsing for NPM dependencies (e.g. @scope/package-name)

