import interning.qual.Interned;

@Interned class InternedClass {
    void foo(Object other) {
        boolean b = other instanceof InternedClass;
    }
}
