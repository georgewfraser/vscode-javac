package org.javacs_server.example;

public class ReferenceConstructor {
    public static ReferenceConstructor instance = new ReferenceConstructor();
    
    public ReferenceConstructor() { }

    {
        new ReferenceConstructor();
    }
}