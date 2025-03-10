<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Dependency Manager Changelog

[中文更新日志](CHANGELOG-CN.md)

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
