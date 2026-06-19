// Karma configuration for Angular tests.
//
// Coverage gate is enforced at 70% lines/statements/functions and 60% branches
// (see `check.global` below). Karma exits non-zero when below — `ng test`
// will fail locally and in CI alike. Uncovered pages are listed in
// .claude/coverage-backlog.md and must close their gap before merge.
//
// CI runs with --browsers=ChromeHeadlessCI; local `ng test` keeps the
// watch-mode default for fast feedback.

module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('karma-junit-reporter'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      jasmine: {},
      clearContext: false,
    },
    jasmineHtmlReporter: { suppressAll: true },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage/angular'),
      subdir: '.',
      reporters: [
        { type: 'html' },
        { type: 'text-summary' },
        { type: 'lcovonly', file: 'lcov.info' },
        { type: 'cobertura', file: 'cobertura-coverage.xml' },
      ],
      check: {
        global: {
          // Hard gate: 70% lines/statements/functions, 60% branches. Karma
          // exits non-zero when below — failing pages listed in
          // .claude/coverage-backlog.md must close their gap before merge.
          statements: 70,
          branches: 60,
          functions: 70,
          lines: 70,
        },
      },
    },
    junitReporter: {
      outputDir: 'test-results/angular',
      outputFile: 'junit.xml',
      useBrowserName: false,
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['Chrome'],
    customLaunchers: {
      ChromeHeadlessCI: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu'],
      },
    },
    restartOnFileChange: true,
  });
};
