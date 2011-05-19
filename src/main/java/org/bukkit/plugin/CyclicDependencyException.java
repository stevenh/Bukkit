
package org.bukkit.plugin;

/**
 * Thrown when attempting to load an invalid Plugin file
 */
public class CyclicDependencyException extends Exception {

    private static final long serialVersionUID = 2451220495228459046L;
    private final Throwable cause;
    private final String message;

    /**
     * Constructs a new CyclicDependencyException based on the given Exception
     *
     * @param throwable Exception that triggered this Exception
     */
    public CyclicDependencyException(Throwable throwable) {
        this(throwable, "Cyclic dependency");
    }

    /**
     * Constructs a new CyclicDependencyException with the given message
     *
     * @param message Brief message explaining the cause of the exception
     */
    public CyclicDependencyException(final String message) {
        this(null, message);
    }

    /**
     * Constructs a new CyclicDependencyException based on the given Exception
     *
     * @param message Brief message explaining the cause of the exception
     * @param throwable Exception that triggered this Exception
     */
    public CyclicDependencyException(final Throwable throwable, final String message) {
        this.cause = null;
        this.message = message;
    }

    /**
     * Constructs a new CyclicDependencyException
     */
    public CyclicDependencyException() {
        this(null, "Cyclic dependency");
    }

    /**
     * If applicable, returns the Exception that triggered this Exception
     *
     * @return Inner exception, or null if one does not exist
     */
    @Override
    public Throwable getCause() {
        return cause;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
