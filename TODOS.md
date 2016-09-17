# Todo

## Polish
* Autocomplete constructor signatures instead of just class name
* Add import code action
* Resolve methods with badly typed arguments
* Autocomplete is using the entire method signature
* Autocomplete annotation fields
* Autocomplete enum options in switch statement
* Hover shows javadoc if available
* Hover show FQ types if not imported
* Javadoc path for hover, autocomplete

## Features 
* Go-to-subclasses
* Signature help

### Refactoring
* Inline method, variable
* Extract method, variable
* Replace for comprehension with loop

### Code generation
* New .java file class boilerplate
* Missing method definition
* Override method
* Add variable
* Enum options
* Cast to type
* Import missing file

### Code lens
* "N references" on method, class
* "N inherited" on class, with generate-override actions

## Optimizations
* Incremental parsing
* Only run attribution and flow phases on method of interest

## Tests
* Hover info

## Lint
* Add 3rd-party linter (findbugs?)