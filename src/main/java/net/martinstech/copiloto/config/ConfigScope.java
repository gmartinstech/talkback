package net.martinstech.copiloto.config;

import java.util.concurrent.Callable;

/**
 * Holds a {@link ScopedValue} carrier for the active {@link AppConfig}.
 * Using Scoped Values (JEP 506) eliminates the need for thread-local variables
 * when propagating configuration through virtual threads.
 */
public final class ConfigScope {
    public static final ScopedValue<AppConfig> CONFIG = ScopedValue.newInstance();

    private ConfigScope() {}

    /**
     * Runs the given operation with {@code config} bound to the scoped value.
     *
     * @param config the configuration to bind
     * @param op     the operation to execute
     * @param <T>    the result type
     * @return the result of the operation
     * @throws Exception if the operation throws
     */
    public static <T> T runWhere(AppConfig config, java.util.concurrent.Callable<T> op) throws Exception {
        return ScopedValue.where(CONFIG, config).call(() -> {
            try {
                return op.call();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
