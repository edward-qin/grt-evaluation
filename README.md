# Fork of Randoop unit test generator for Java

Randoop is a unit test generator for Java.
It automatically creates unit tests for your classes, in JUnit format.
This fork is focused on the implementation and evaluation of the [GRT](https://ieeexplore.ieee.org/stampPDF/getPDF.jsp?tp=&arnumber=7372010&ref=&tag=1) paper. In particular, it aims to verify the coverage/mutation score and defect
detection (via Defects4j) evaluation results from the paper.

## Setup

The main repositories used to evaluate GRT are as follows:
* [This repository](https://github.com/edward-qin/grt-evaluation): A fork of Randoop (with flags to enable GRT
  techniques)
* [Coverage and Mutation Score](https://github.com/randoop/grt-testing/tree/776styjsu-diff-patch):
  Evaluation of code
  coverage, branch coverage, and mutation score on 32 open source programs from the original GRT paper
* [Defects4j](https://github.com/edward-qin/defects4j-grt): A fork of Defects4j, a dataset of known defects found in real open-source programs used in defect
  detection from the original GRT paper

### Coverage and Mutation Score Setup

1. Clone the repository
```bash
git clone git@github.com:randoop/grt-testing.git
cd grt-testing
```
2. Follow the steps in `scripts/mutation-prerequisites.md` and `scripts/mutation-repro.md`
   * You may need to copy the function definitions of `usejdk8` and `usejdk11` directly into `mutation.sh` or
    `mutation-all.sh`
   * You may need to run the example command with a time limit: `./mutation.sh -vr -t <num_seconds> commons-lang3-3.0`
   * Instead of cloning the `randoop` repository and then removing it, you can instead copy from this fork of randoop.
```bash
usejdk11
cd <path_to_root_of_grt-evaluation>
./gradlew shadowJar
mv -f build/libs/randoop-all-4.3.3.jar agent/replacecall/build/libs/replacecall-4.3.3.jar
<path_to_grt-testing>/scripts/build
usejdk8
```

### Defects4j Setup

1. Clone the repository
```bash
git clone git@github.com:edward-qin/defects4j-grt.git
cd defects4j-grt
```
2. Follow the steps to setup [Defects4j](https://github.com/edward-qin/defects4j-grt?tab=readme-ov-file#steps-to-set-up-defects4j)
3. TODO: some other script steps here
   * You can alternatively setup using `grt-evaluation/scripts/defects4j_README.md`

## Learn about Randoop:

* [Randoop homepage](https://randoop.github.io/randoop/)
* [Randoop manual](https://randoop.github.io/randoop/manual/index.html)
* [Randoop release](https://github.com/randoop/randoop/releases/latest)
* [Randoop developer's manual](https://randoop.github.io/randoop/manual/dev.html)
* [Randoop Javadoc](https://randoop.github.io/randoop/api/)

## Directory structure

* `agent` - subprojects for Java agents (load-time bytecode rewriting)
* `gradle` - the Gradle wrapper directory (*Should not be edited*)
* `lib` - jar files for local copies of libraries not available via Maven
* `scripts` - git hook scripts
* `src` - source directories for Randoop, including
    * `coveredTest` - source for JUnit tests of the covered-class Java agent
    * `distribution` - resource files for creating the distribution zip file
    * `docs` - [documentation]("https://randoop.github.io/randoop/"), including the manual and resources
    * `javadoc` - resource files for creating [API documentation](https://randoop.github.io/randoop/api/)
    * `main` - Randoop source code
    * `replacecallTest` - source for JUnit tests of the replacecall Java agent
    * `systemTest` - source for Randoop system tests
    * `test` - source for JUnit tests of Randoop
    * `testInput` - source for libraries used in Randoop testing

The source directories follow the conventions of the Gradle Java plugin, where
each directory has a _java_ subdirectory containing Java source, and,
in some cases, a _resources_ subdirectory containing other files.
