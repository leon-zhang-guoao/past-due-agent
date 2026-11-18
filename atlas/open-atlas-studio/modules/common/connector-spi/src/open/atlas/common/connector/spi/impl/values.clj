(ns open.atlas.common.connector.spi.impl.values
  "Conversion of one mapped value to the class the Contract 2.0 result check
   accepts for its declared type (D2.20). Shared by every connector that cannot
   produce those classes natively -- sql (JDBC Double, java.sql.Date, text) and
   api/nifi (JSON numbers and text) -- so the rules exist once.

   The result check (`semantic-record/scalar?`) accepts exactly BigDecimal,
   LocalDate and Instant for :decimal, :date and :datetime, and does not coerce.

   A value that cannot be converted without inventing information is returned
   unchanged, so the result check refuses it instead of the connector guessing.
   A zoneless date-time is the case in point: it is not an instant until a zone
   is chosen.")

(def ^:private offset-text
  ;; ISO-8601 or PostgreSQL text form with an explicit offset:
  ;; 2026-09-29T08:00:00Z, 2026-09-29 08:00:00.123+00, 2026-09-29 08:00:00+05:30
  #"^(\d{4}-\d{2}-\d{2})[Tt ](\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)([Zz]|[+-]\d{2}(?::?\d{2})?)$")

(def ^:private date-text #"^\d{4}-\d{2}-\d{2}$")

(defn- text->instant [^String s]
  (when-let [[_ d t off] (re-matches offset-text s)]
    (let [off (cond
                (#{"Z" "z"} off) "Z"
                (= 3 (count off)) (str off ":00")          ; +00 -> +00:00
                (= 5 (count off)) (str (subs off 0 3) ":" (subs off 3))
                :else off)]
      (try (.toInstant (java.time.OffsetDateTime/parse (str d "T" t off)))
           (catch java.time.format.DateTimeParseException _ nil)))))

(defn- ->decimal [v]
  (cond
    (instance? java.math.BigDecimal v) v
    ;; valueOf keeps the shortest decimal that round-trips the double
    ;; (0.1 -> 0.1), not its full binary expansion.
    (or (instance? Double v) (instance? Float v))
    (let [d (double v)] (if (Double/isFinite d) (java.math.BigDecimal/valueOf d) v))
    (integer? v) (bigdec v)
    :else v))

(defn- ->date [v]
  (cond
    (instance? java.time.LocalDate v) v
    (instance? java.sql.Date v) (.toLocalDate ^java.sql.Date v)
    (and (string? v) (re-matches date-text v))
    (try (java.time.LocalDate/parse v) (catch java.time.format.DateTimeParseException _ v))
    :else v))

(defn- ->instant [v]
  (cond
    (instance? java.time.Instant v) v
    (instance? java.sql.Timestamp v) (.toInstant ^java.sql.Timestamp v)
    (instance? java.time.OffsetDateTime v) (.toInstant ^java.time.OffsetDateTime v)
    (instance? java.time.ZonedDateTime v) (.toInstant ^java.time.ZonedDateTime v)
    (string? v) (or (text->instant v) v)
    ;; LocalDateTime and anything else: no zone, no conversion (see ns doc).
    :else v))

(def converters
  "Declared type -> converter of one non-nil value."
  {:decimal ->decimal :date ->date :datetime ->instant})
