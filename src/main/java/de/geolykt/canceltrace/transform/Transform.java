package de.geolykt.canceltrace.transform;

public interface Transform {

    // internalName will be something like ("com/example/main")
    public boolean canTransform(String internalName);

    public byte[] transform(byte[] input);
}
