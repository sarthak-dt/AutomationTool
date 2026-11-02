package com.stellar.platform.parser.mf4.dynamicschema;

/**
 * ASAM algebraic CC formula ({@code cc_type = 3}).
 * <p>
 * Supports numbers, the raw variable {@code X}, parentheses, {@code + - * / ^},
 * and {@code sin cos tan asin acos atan sqrt exp ln log abs floor ceil}.
 * Anything else is rejected so the caller can keep the raw sample and report
 * that the conversion was not applied.
 */
final class MdfAlgebraicFormula {

    private MdfAlgebraicFormula() {
    }

    static Prepared prepare(String formula) {
        if (formula == null || formula.isBlank()) {
            return Prepared.rejected("algebraic formula is missing");
        }
        Parser parser = new Parser(formula.replace("\u0000", "").trim());
        Expr expr = parser.parseExpr();
        parser.skip();
        if (parser.failed()) {
            return Prepared.rejected(parser.error);
        }
        if (parser.i < parser.s.length()) {
            return Prepared.rejected("unsupported algebraic formula '" + formula.trim() + "'");
        }
        return new Prepared(expr, null);
    }

    record Prepared(Expr expr, String error) {
        static Prepared rejected(String error) {
            return new Prepared(null, error);
        }

        boolean accepted() {
            return expr != null && error == null;
        }

        double eval(double x) {
            return expr.eval(x);
        }
    }

    private interface Expr {
        double eval(double x);
    }

    private static final class Parser {
        private final String s;
        private int i;
        private String error;

        private Parser(String s) {
            this.s = s;
        }

        private boolean failed() {
            return error != null;
        }

        private Expr parseExpr() {
            Expr left = parseTerm();
            if (failed()) {
                return null;
            }
            while (true) {
                skip();
                if (match('+')) {
                    Expr right = parseTerm();
                    left = binary(left, right, '+');
                } else if (match('-')) {
                    Expr right = parseTerm();
                    left = binary(left, right, '-');
                } else {
                    return left;
                }
            }
        }

        private Expr parseTerm() {
            Expr left = parsePower();
            if (failed()) {
                return null;
            }
            while (true) {
                skip();
                if (match('*')) {
                    Expr right = parsePower();
                    left = binary(left, right, '*');
                } else if (match('/')) {
                    Expr right = parsePower();
                    left = binary(left, right, '/');
                } else {
                    return left;
                }
            }
        }

        private Expr parsePower() {
            Expr left = parseUnary();
            if (failed()) {
                return null;
            }
            skip();
            if (match('^')) {
                Expr right = parseUnary();
                return binary(left, right, '^');
            }
            return left;
        }

        private Expr parseUnary() {
            skip();
            if (match('+')) {
                return parseUnary();
            }
            if (match('-')) {
                Expr inner = parseUnary();
                return x -> -(inner == null ? Double.NaN : inner.eval(x));
            }
            return parsePrimary();
        }

        private Expr parsePrimary() {
            skip();
            if (failed()) {
                return null;
            }
            if (match('(')) {
                Expr inner = parseExpr();
                skip();
                if (!match(')')) {
                    error = "algebraic formula is missing ')'";
                }
                return inner;
            }
            if (i < s.length() && (s.charAt(i) == 'X' || s.charAt(i) == 'x') && !identChar(peek(1))) {
                i++;
                return x -> x;
            }
            if (i < s.length() && (Character.isLetter(s.charAt(i)))) {
                int start = i;
                while (i < s.length() && Character.isLetter(s.charAt(i))) {
                    i++;
                }
                String name = s.substring(start, i).toLowerCase(java.util.Locale.ROOT);
                skip();
                if (!match('(')) {
                    error = "unsupported algebraic token '" + name + "'";
                    return null;
                }
                Expr arg = parseExpr();
                skip();
                if (!match(')')) {
                    error = "algebraic formula is missing ')'";
                    return null;
                }
                return function(name, arg);
            }
            return parseNumber();
        }

        private Expr parseNumber() {
            skip();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            boolean digit = false;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                digit = true;
                i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int e = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                int expStart = i;
                while (i < s.length() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i == expStart) {
                    i = e;
                }
            }
            if (!digit) {
                error = "unsupported algebraic formula '" + s + "'";
                return null;
            }
            try {
                double value = Double.parseDouble(s.substring(start, i));
                return x -> value;
            } catch (NumberFormatException ex) {
                error = "unsupported algebraic number '" + s.substring(start, i) + "'";
                return null;
            }
        }

        private Expr function(String name, Expr arg) {
            return switch (name) {
                case "sin" -> x -> Math.sin(arg.eval(x));
                case "cos" -> x -> Math.cos(arg.eval(x));
                case "tan" -> x -> Math.tan(arg.eval(x));
                case "asin" -> x -> Math.asin(arg.eval(x));
                case "acos" -> x -> Math.acos(arg.eval(x));
                case "atan" -> x -> Math.atan(arg.eval(x));
                case "sqrt" -> x -> Math.sqrt(arg.eval(x));
                case "exp" -> x -> Math.exp(arg.eval(x));
                case "ln" -> x -> Math.log(arg.eval(x));
                case "log" -> x -> Math.log10(arg.eval(x));
                case "abs" -> x -> Math.abs(arg.eval(x));
                case "floor" -> x -> Math.floor(arg.eval(x));
                case "ceil" -> x -> Math.ceil(arg.eval(x));
                default -> {
                    error = "unsupported algebraic function '" + name + "'";
                    yield null;
                }
            };
        }

        private Expr binary(Expr left, Expr right, char op) {
            if (left == null || right == null) {
                return null;
            }
            return x -> {
                double a = left.eval(x);
                double b = right.eval(x);
                return switch (op) {
                    case '+' -> a + b;
                    case '-' -> a - b;
                    case '*' -> a * b;
                    case '/' -> a / b;
                    case '^' -> Math.pow(a, b);
                    default -> Double.NaN;
                };
            };
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean match(char c) {
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private char peek(int ahead) {
            int p = i + ahead;
            return p >= 0 && p < s.length() ? s.charAt(p) : 0;
        }

        private static boolean identChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }
}
