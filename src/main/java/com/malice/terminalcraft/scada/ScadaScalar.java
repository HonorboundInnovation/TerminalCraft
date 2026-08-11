package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.DeviceValue;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** One bounded scalar value accepted by the historian and alarm engine. */
public record ScadaScalar(Type type, double numberValue, boolean booleanValue, String textValue) {
    public static final int MAX_TEXT_CHARS = 256;

    public enum Type { NUMBER, BOOLEAN, STRING }

    public ScadaScalar {
        type = Objects.requireNonNull(type, "type");
        if (!Double.isFinite(numberValue)) throw new IllegalArgumentException("SCADA number must be finite");
        textValue = Objects.requireNonNullElse(textValue, "");
        if (textValue.length() > MAX_TEXT_CHARS) textValue = textValue.substring(0, MAX_TEXT_CHARS);
    }

    public static ScadaScalar number(double value) { return new ScadaScalar(Type.NUMBER, value, false, ""); }
    public static ScadaScalar bool(boolean value) { return new ScadaScalar(Type.BOOLEAN, 0, value, ""); }
    public static ScadaScalar text(String value) { return new ScadaScalar(Type.STRING, 0, false, value); }

    /** Parses the shell/device wire form: n:12.5, b:true, s:text, or an unprefixed string. */
    public static ScadaScalar parseToken(String token) {
        String value = Objects.requireNonNullElse(token, "");
        if (value.startsWith("n:")) return number(Double.parseDouble(value.substring(2)));
        if (value.startsWith("b:")) {
            String booleanText = value.substring(2);
            if (!"true".equalsIgnoreCase(booleanText) && !"false".equalsIgnoreCase(booleanText)) {
                throw new IllegalArgumentException("boolean must be b:true or b:false");
            }
            return bool(Boolean.parseBoolean(booleanText));
        }
        return text(value.startsWith("s:") ? value.substring(2) : value);
    }

    public static Optional<ScadaScalar> from(DeviceValue value) {
        if (value instanceof DeviceValue.NumberValue number) return Optional.of(number(number.value()));
        if (value instanceof DeviceValue.BooleanValue bool) return Optional.of(bool(bool.value()));
        if (value instanceof DeviceValue.StringValue text) return Optional.of(text(text.value()));
        return Optional.empty();
    }

    public DeviceValue toDeviceValue() {
        return switch (type) {
            case NUMBER -> DeviceValue.of(numberValue);
            case BOOLEAN -> DeviceValue.of(booleanValue);
            case STRING -> DeviceValue.of(textValue);
        };
    }

    public String display() {
        return switch (type) {
            case NUMBER -> numberValue == Math.rint(numberValue)
                    ? Long.toString((long) numberValue)
                    : String.format(Locale.ROOT, "%.3f", numberValue);
            case BOOLEAN -> booleanValue ? "true" : "false";
            case STRING -> textValue.replace('\n', ' ').replace('\r', ' ');
        };
    }

    public boolean equivalent(ScadaScalar other) {
        if (other == null || type != other.type) return false;
        return switch (type) {
            case NUMBER -> Double.compare(numberValue, other.numberValue) == 0;
            case BOOLEAN -> booleanValue == other.booleanValue;
            case STRING -> textValue.equals(other.textValue);
        };
    }
}
