package org.javacs_server.example;

public class AutocompleteScopes {
    static void testOuterStaticMethod() { }
    void testOuterMethods() { }

    static class Super {
        static void testInheritedStaticMethod() { }
        void testInheritedMethods() { }
    }

    static class StaticSub extends Super {
        void test(String testArguments) {
            int testLocalVariables;
            t;
            // Locals
            // YES: testLocalVariables, testArguments
            //
            // Static methods in enclosing scopes
            // YES: testStatic
            // YES: testOuterStaticMethod
            //
            // Virtual methods in enclosing scopes
            // NO: testInner
            // YES: test
            // NO: testOuterMethods
            //
            // Inherited static methods
            // YES: testInheritedStaticMethod
            //
            // Inherited virtual methods
            // YES: testInheritedMethods
            //
            // this/super in enclosing scopes
            // YES: this, super
            // YES: StaticSub.this, StaticSub.super
            StaticSub.;
            // NO: AutocompleteScopes.this, AutocompleteScopes.super
            AutocompleteScopes.;
            // NO: Super.this, Super.super
            Super.;

            new Object() {
                void testInner() {
                    t;
                    // Locals
                    // YES: testLocalVariables, testArguments
                    //
                    // Static methods in enclosing scopes
                    // YES: testStatic
                    // YES: testOuterStaticMethod
                    //
                    // Virtual methods in enclosing scopes
                    // YES: testInner
                    // YES: test
                    // NO: testOuterMethods
                    //
                    // Inherited static methods
                    // YES: testInheritedStaticMethod
                    //
                    // Inherited virtual methods
                    // YES: testInheritedMethods
                    //
                    // this/super in enclosing scopes
                    // YES: this, super
                    // YES: StaticSub.this, StaticSub.super
                    StaticSub.;
                    // NO: AutocompleteScopes.this, AutocompleteScopes.super
                    AutocompleteScopes.;
                    // NO: Super.this, Super.super
                    Super.;
                }
            };
        }

        static void testStatic(String testArguments) {
            int testLocalVariables;
            t;
            // Locals
            // YES: testLocalVariables, testArguments
            //
            // Static methods in enclosing scopes
            // YES: testStatic
            // YES: testOuterStaticMethod
            //
            // Virtual methods in enclosing scopes
            // NO: testInner
            // NO: test
            // NO: testOuterMethods
            //
            // Inherited static methods
            // YES: testInheritedStaticMethod
            //
            // Inherited virtual methods
            // NO: testInheritedMethods
            //
            // this/super in enclosing scopes
            // NO: this, super
            // NO: StaticSub.this, StaticSub.super
            StaticSub.;
            // NO: AutocompleteScopes.this, AutocompleteScopes.super
            AutocompleteScopes.;
            // NO: Super.this, Super.super
            Super.;

            new Object() {
                void testInner() {
                    t;
                    // Locals
                    // YES: testLocalVariables, testArguments
                    //
                    // Static methods in enclosing scopes
                    // YES: testStatic
                    // YES: testOuterStaticMethod
                    //
                    // Virtual methods in enclosing scopes
                    // YES: testInner
                    // NO: test
                    // NO: testOuterMethods
                    //
                    // Inherited static methods
                    // YES: testInheritedStaticMethod
                    //
                    // Inherited virtual methods
                    // NO: testInheritedMethods
                    //
                    // this/super in enclosing scopes
                    // YES: this, super
                    // NO: StaticSub.this, StaticSub.super
                    StaticSub.;
                    // NO: AutocompleteScopes.this, AutocompleteScopes.super
                    AutocompleteScopes.;
                    // NO: Super.this, Super.super
                    Super.;
                }
            };
        }
    }

    class Sub extends Super {
        void test(String testArguments) {
            int testLocalVariables;
            t;
            // Locals
            // YES: testLocalVariables, testArguments
            //
            // Methods in enclosing scopes
            // NO: testInner
            // YES: test
            // YES: testOuterMethods, testOuterStaticMethod
            //
            // Inherited methods
            // YES: testInheritedMethods, testInheritedStaticMethod
            //
            // this/super in enclosing scopes
            // YES: this, super
            // YES: Sub.this, Sub.super
            Sub.;
            // YES: AutocompleteScopes.this, AutocompleteScopes.super
            AutocompleteScopes.;
            // NO: Super.this, Super.super
            Super.;

            new Object() {
                void testInner() {
                    t;
                    // Locals
                    // YES: testLocalVariables, testArguments
                    //
                    // Methods in enclosing scopes
                    // YES: testInner
                    // YES: test
                    // YES: testOuterMethods, testOuterStaticMethod
                    //
                    // Inherited methods
                    // YES: testInheritedMethods, testInheritedStaticMethod
                    //
                    // this/super in enclosing scopes
                    // YES: this, super
                    // YES: Sub.this, Sub.super
                    Sub.;
                    // YES: AutocompleteScopes.this, AutocompleteScopes.super
                    AutocompleteScopes.;
                    // NO: Super.this, Super.super
                    Super.;
                }
            };
        }
    }
}