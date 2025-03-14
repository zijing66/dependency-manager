<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Dependency Manager Changelog

[中文更新日志](CHANGELOG-CN.md)

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

- Support deleting failed Maven downloads  
- Support deleting SNAPSHOT packages and specified groupId/artifactId 
- Compatibility optimization and search performance optimization

### Fixed

- Fixed compatibility issues with IntelliJ IDEA 2021.3
- Fixed ContentFactory.getInstance() method not found error
- Fixed kotlin.enums package not found error
- Fixed JVM signature clash in DependencyType enum

## [Unreleased]

### Added

- Dependency analysis automation
- Maven local repository cleanup
- Interactive preview interface
- Multi-threaded progress monitoring
