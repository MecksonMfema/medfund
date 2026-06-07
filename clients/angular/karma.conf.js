// Karma configuration for Angular tests.
//
// Coverage thresholds start at the current baseline minus a small margin so a
// PR cannot regress coverage from where we are today. Ratchet these upward
// (+5% per month) toward the long-term target: 60% lines / 50% branches.
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
          statements: 35,
          branches: 25,
          functions: 35,
          lines: 35,
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
