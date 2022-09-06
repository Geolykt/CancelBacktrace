package de.geolykt.canceltrace.transform;

class InjectionException extends Exception {

    private static final long serialVersionUID = 3052113038080616178L;

    InjectionException(String message, Throwable cause) {
        super(message, cause);
    }

    InjectionException(String message) {
        super(message);
    }
}
