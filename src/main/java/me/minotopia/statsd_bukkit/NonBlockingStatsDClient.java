package me.minotopia.statsd_bukkit;

import org.bukkit.plugin.Plugin;

import java.nio.charset.Charset;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * A simple StatsD client implementation facilitating metrics recording.
 *
 * <p>Upon instantiation, this client will establish a socket connection to a StatsD instance
 * running on the specified host and port. Metrics are then sent over this connection as they are
 * received by the client.
 * </p>
 *
 * <p>Three key methods are provided for the submission of data-points for the application under
 * scrutiny:</p>
 * <ul>
 * <li>{@link #count} - adds one to the value of the specified named counter</li>
 * <li>{@link #recordGaugeValue} - records the latest fixed value for the specified named gauge</li>
 * <li>{@link #recordExecutionTime} - records an execution time in milliseconds for the specified named operation</li>
 * </ul>
 * <p>From the perspective of the application, these methods are non-blocking, with the resulting
 * IO operations being carried out in a separate thread. Furthermore, these methods are guaranteed
 * not to throw an exception which may disrupt application execution.
 * </p>
 *
 * @author Tom Denley
 */
public class NonBlockingStatsDClient extends ConvenienceMethodProvidingStatsDClient {

    private static final Charset STATS_D_ENCODING = Charset.forName("UTF-8");

    private static final StatsDClientErrorHandler NO_OP_HANDLER = e -> { /* No-op */ };

    private final String prefix;
    private final NonBlockingUdpSender sender;

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are consumed, guaranteeing
     * that failures in metrics will not affect normal code execution.
     *
     * @param prefix   the prefix to apply to keys sent via this client (can be null or empty for no prefix)
     * @param hostname the host name of the targeted StatsD server
     * @param port     the port of the targeted StatsD server
     * @param plugin   the plugin to use for registering the tasks to save data
     * @throws StatsDClientException if the client could not be started
     */
    public NonBlockingStatsDClient(String prefix, String hostname, int port, Plugin plugin) throws StatsDClientException {
        this(prefix, hostname, port, plugin, NO_OP_HANDLER);
    }

    /**
     * Create a new StatsD client communicating with a StatsD instance on the
     * specified host and port. All messages send via this client will have
     * their keys prefixed with the specified string. The new client will
     * attempt to open a connection to the StatsD server immediately upon
     * instantiation, and may throw an exception if that a connection cannot
     * be established. Once a client has been instantiated in this way, all
     * exceptions thrown during subsequent usage are passed to the specified
     * handler and then consumed, guaranteeing that failures in metrics will
     * not affect normal code execution.
     *
     * @param prefix       the prefix to apply to keys sent via this client (can be null or empty for no prefix)
     * @param hostname     the host name of the targeted StatsD server
     * @param port         the port of the targeted StatsD server
     * @param errorHandler handler to use when an exception occurs during usage
     * @param plugin       the plugin to use for registering the tasks to save data
     * @throws StatsDClientException if the client could not be started
     */
    public NonBlockingStatsDClient(String prefix, String hostname, int port, Plugin plugin, StatsDClientErrorHandler errorHandler) throws StatsDClientException {
        this.prefix = (prefix == null || prefix.trim().isEmpty()) ? "" : (prefix.trim() + ".");

        try {
            this.sender = new NonBlockingUdpSender(hostname, port, STATS_D_ENCODING, errorHandler, plugin);
        } catch (Exception e) {
            throw new StatsDClientException("Failed to start StatsD client", e);
        }
    }

    @Override
    public void count(String aspect, long delta, double sampleRate) {
        send(messageFor(aspect, Long.toString(delta), "c", sampleRate));
    }

    @Override
    public void recordGaugeValue(String aspect, long value) {
        recordGaugeCommon(aspect, Long.toString(value), value < 0, false);
    }

    @Override
    public void recordGaugeValue(String aspect, double value) {
        recordGaugeCommon(aspect, stringValueOf(value), value < 0, false);
    }

    @Override
    public void recordGaugeDelta(String aspect, long value) {
        recordGaugeCommon(aspect, Long.toString(value), value < 0, true);
    }

    @Override
    public void recordGaugeDelta(String aspect, double value) {
        recordGaugeCommon(aspect, stringValueOf(value), value < 0, true);
    }

    private void recordGaugeCommon(String aspect, String value, boolean negative, boolean delta) {
        final StringBuilder message = new StringBuilder();
        if (!delta && negative) {
            message.append(messageFor(aspect, "0", "g")).append('\n');
        }
        message.append(messageFor(aspect, (delta && !negative) ? ("+" + value) : value, "g"));
        send(message.toString());
    }

    /**
     * StatsD supports counting unique occurrences of events between flushes, Call this method to records an occurrence
     * of the specified named event.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect    the name of the set
     * @param eventName the value to be added to the set
     */
    @Override
    public void recordSetEvent(String aspect, String eventName) {
        send(messageFor(aspect, eventName, "s"));
    }

    /**
     * Records an execution time in milliseconds for the specified named operation.
     *
     * <p>This method is non-blocking and is guaranteed not to throw an exception.</p>
     *
     * @param aspect   the name of the timed operation
     * @param timeInMs the time in milliseconds
     */
    @Override
    public void recordExecutionTime(String aspect, long timeInMs, double sampleRate) {
        send(messageFor(aspect, Long.toString(timeInMs), "ms", sampleRate));
    }

    private String messageFor(String aspect, String value, String type) {
        return messageFor(aspect, value, type, 1.0);
    }

    private String messageFor(String aspect, String value, String type, double sampleRate) {
        final String message = prefix + aspect + ':' + value + '|' + type;
        return (sampleRate == 1.0)
                ? message
                : (message + "|@" + stringValueOf(sampleRate));
    }

    private void send(final String message) {
        sender.send(message);
    }

    private String stringValueOf(double value) {
        NumberFormat formatter = NumberFormat.getInstance(Locale.US);
        formatter.setGroupingUsed(false);
        formatter.setMaximumFractionDigits(19);
        return formatter.format(value);
    }
}
