package com.luminaplayer.ai;

/**
 * Supported languages for Whisper speech-to-text transcription.
 * Each language includes an optional initial prompt in its native script
 * to prime the Whisper decoder for more accurate transcription.
 */
public enum WhisperLanguage {

    AUTO("auto", "Auto-detect", ""),
    ENGLISH("en", "English", ""),
    SPANISH("es", "Spanish", "Transcripción del audio en español."),
    FRENCH("fr", "French", "Transcription de l'audio en français."),
    GERMAN("de", "German", "Transkription der Audiodatei auf Deutsch."),
    ITALIAN("it", "Italian", "Trascrizione dell'audio in italiano."),
    PORTUGUESE("pt", "Portuguese", "Transcrição do áudio em português."),
    RUSSIAN("ru", "Russian", "\u0422\u0440\u0430\u043d\u0441\u043a\u0440\u0438\u043f\u0446\u0438\u044f \u0430\u0443\u0434\u0438\u043e \u043d\u0430 \u0440\u0443\u0441\u0441\u043a\u043e\u043c \u044f\u0437\u044b\u043a\u0435."),
    JAPANESE("ja", "Japanese", "\u3053\u308c\u306f\u65e5\u672c\u8a9e\u306e\u97f3\u58f0\u306e\u66f8\u304d\u8d77\u3053\u3057\u3067\u3059\u3002"),
    KOREAN("ko", "Korean", "\uc774\uac83\uc740 \ud55c\uad6d\uc5b4 \uc74c\uc131\uc758 \uc804\uc0ac\uc785\ub2c8\ub2e4."),
    CHINESE("zh", "Chinese", "\u8fd9\u662f\u4e2d\u6587\u97f3\u9891\u7684\u8f6c\u5f55\u3002"),
    ARABIC("ar", "Arabic", "\u0647\u0630\u0627 \u0646\u0633\u062e \u0635\u0648\u062a\u064a \u0628\u0627\u0644\u0644\u063a\u0629 \u0627\u0644\u0639\u0631\u0628\u064a\u0629."),
    HINDI("hi", "Hindi", "\u092f\u0939 \u0939\u093f\u0902\u0926\u0940 \u092e\u0947\u0902 \u0911\u0921\u093f\u092f\u094b \u0915\u093e \u092a\u094d\u0930\u0924\u093f\u0932\u0947\u0916\u0928 \u0939\u0948\u0964"),
    TAMIL("ta", "Tamil", "\u0b87\u0ba4\u0bc1 \u0ba4\u0bae\u0bbf\u0bb4\u0bcd \u0b95\u0bc7\u0bb3\u0bcd\u0bb5\u0bbf\u0baf\u0bbf\u0ba9\u0bcd \u0b8e\u0bb4\u0bc1\u0ba4\u0bcd\u0ba4\u0bc1 \u0baa\u0bc6\u0baf\u0bb0\u0bcd\u0baa\u0bcd\u0baa\u0bc1."),
    TELUGU("te", "Telugu", "\u0c07\u0c26\u0c3f \u0c24\u0c46\u0c32\u0c41\u0c17\u0c41\u0c32\u0c4b \u0c06\u0c21\u0c3f\u0c2f\u0c4b \u0c2f\u0c4a\u0c15\u0c4d\u0c15 \u0c32\u0c3f\u0c2a\u0c3f\u0c2f\u0c02\u0c24\u0c30\u0c23."),
    BENGALI("bn", "Bengali", "\u098f\u099f\u09bf \u09ac\u09be\u0982\u09b2\u09be \u0985\u09a1\u09bf\u0993\u09b0 \u09aa\u09cd\u09b0\u09a4\u09bf\u09b2\u09bf\u09aa\u09bf."),
    KANNADA("kn", "Kannada", "\u0c87\u0ca6\u0cc1 \u0c95\u0ca8\u0ccd\u0ca8\u0ca1\u0ca6\u0cb2\u0ccd\u0cb2\u0cbf \u0c86\u0ca1\u0cbf\u0caf\u0ccb \u0cb2\u0cbf\u0caa\u0ccd\u0caf\u0c82\u0ca4\u0cb0\u0ca3."),
    MALAYALAM("ml", "Malayalam", "\u0d07\u0d24\u0d4d \u0d2e\u0d32\u0d2f\u0d3e\u0d33\u0d02 \u0d13\u0d21\u0d3f\u0d2f\u0d4b\u0d2f\u0d41\u0d1f\u0d46 \u0d2a\u0d4d\u0d30\u0d24\u0d3f\u0d32\u0d3f\u0d2a\u0d3f\u0d2f\u0d3e\u0d23\u0d4d."),
    MARATHI("mr", "Marathi", "\u0939\u0947 \u092e\u0930\u093e\u0920\u0940\u0924\u0940\u0932 \u0911\u0921\u093f\u0913\u091a\u0947 \u092a\u094d\u0930\u0924\u093f\u0932\u0947\u0916\u0928 \u0906\u0939\u0947."),
    GUJARATI("gu", "Gujarati", "\u0a86 \u0a97\u0ac1\u0a9c\u0ab0\u0abe\u0aa4\u0ac0\u0aae\u0abe\u0a82 \u0a91\u0aa1\u0abf\u0a93\u0aa8\u0ac1\u0a82 \u0aaa\u0acd\u0ab0\u0aa4\u0abf\u0ab2\u0ac7\u0a96\u0aa8 \u0a9b\u0ac7."),
    PUNJABI("pa", "Punjabi", "\u0a07\u0a39 \u0a2a\u0a70\u0a1c\u0a3e\u0a2c\u0a40 \u0a06\u0a21\u0a40\u0a13 \u0a26\u0a40 \u0a1f\u0a4d\u0a30\u0a3e\u0a02\u0a38\u0a15\u0a4d\u0a30\u0a3f\u0a2a\u0a36\u0a28 \u0a39\u0a48."),
    URDU("ur", "Urdu", "\u06cc\u06c1 \u0627\u0631\u062f\u0648 \u0622\u0688\u06cc\u0648 \u06a9\u06cc \u0679\u0631\u0627\u0646\u0633\u06a9\u0631\u067e\u0634\u0646 \u06c1\u06d2\u06d4"),
    DUTCH("nl", "Dutch", "Transcriptie van het audiofragment in het Nederlands."),
    POLISH("pl", "Polish", "Transkrypcja audio w j\u0119zyku polskim."),
    SWEDISH("sv", "Swedish", "Transkription av ljudet p\u00e5 svenska."),
    TURKISH("tr", "Turkish", "T\u00fcrk\u00e7e ses dosyas\u0131n\u0131n transkripsiyonu."),
    CZECH("cs", "Czech", "P\u0159epis zvukov\u00e9ho z\u00e1znamu v \u010de\u0161tin\u011b."),
    GREEK("el", "Greek", "\u039c\u03b5\u03c4\u03b1\u03b3\u03c1\u03b1\u03c6\u03ae \u03c4\u03bf\u03c5 \u03ae\u03c7\u03bf\u03c5 \u03c3\u03c4\u03b1 \u03b5\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ac."),
    ROMANIAN("ro", "Romanian", "Transcrip\u021bia audio \u00een limba rom\u00e2n\u0103."),
    HUNGARIAN("hu", "Hungarian", "A hangfelv\u00e9tel \u00e1t\u00edr\u00e1sa magyarul."),
    UKRAINIAN("uk", "Ukrainian", "\u0422\u0440\u0430\u043d\u0441\u043a\u0440\u0438\u043f\u0446\u0456\u044f \u0430\u0443\u0434\u0456\u043e \u0443\u043a\u0440\u0430\u0457\u043d\u0441\u044c\u043a\u043e\u044e \u043c\u043e\u0432\u043e\u044e."),
    INDONESIAN("id", "Indonesian", "Transkripsi audio dalam bahasa Indonesia."),
    THAI("th", "Thai", "\u0e19\u0e35\u0e48\u0e04\u0e37\u0e2d\u0e01\u0e32\u0e23\u0e16\u0e2d\u0e14\u0e40\u0e2a\u0e35\u0e22\u0e07\u0e20\u0e32\u0e29\u0e32\u0e44\u0e17\u0e22."),
    VIETNAMESE("vi", "Vietnamese", "\u0110\u00e2y l\u00e0 b\u1ea3n phi\u00ean \u00e2m c\u1ee7a \u00e2m thanh ti\u1ebfng Vi\u1ec7t."),
    PERSIAN("fa", "Persian", "\u0627\u06cc\u0646 \u0631\u0648\u0646\u0648\u06cc\u0633\u06cc \u0635\u0648\u062a\u06cc \u0628\u0647 \u0632\u0628\u0627\u0646 \u0641\u0627\u0631\u0633\u06cc \u0627\u0633\u062a."),
    HEBREW("he", "Hebrew", "\u05d6\u05d4\u05d5 \u05ea\u05de\u05dc\u05d5\u05dc \u05e9\u05dc \u05d4\u05e9\u05de\u05e2 \u05d1\u05e2\u05d1\u05e8\u05d9\u05ea."),
    FINNISH("fi", "Finnish", "T\u00e4m\u00e4 on suomenkielisen \u00e4\u00e4nitteen litterointi."),
    DANISH("da", "Danish", "Transskription af lyden p\u00e5 dansk."),
    NORWEGIAN("no", "Norwegian", "Transkripsjon av lyden p\u00e5 norsk."),
    MALAY("ms", "Malay", "Transkripsi audio dalam bahasa Melayu."),
    FILIPINO("tl", "Filipino", "Ito ang transkripsyon ng audio sa Filipino."),
    CATALAN("ca", "Catalan", "Transcripci\u00f3 de l'\u00e0udio en catal\u00e0."),
    BURMESE("my", "Burmese", "\u1024\u1000\u1030 \u1019\u103c\u1014\u103a\u1019\u102c\u1018\u102c\u101e\u102c \u1021\u101e\u1036\u1016\u102d\u102f\u1004\u103a\u101b\u102c \u101b\u1031\u1038\u101e\u102c\u1038\u1001\u103b\u1000\u103a\u1016\u103c\u1005\u103a\u1015\u102b\u1010\u101a\u103a."),
    NEPALI("ne", "Nepali", "\u092f\u094b \u0928\u0947\u092a\u093e\u0932\u0940 \u0905\u0921\u093f\u092f\u094b\u0915\u094b \u092a\u094d\u0930\u0924\u093f\u0932\u093f\u092a\u093f \u0939\u094b\u0964"),
    SINHALA("si", "Sinhala", "\u0db8\u0dd9\u0dba \u0dc3\u0dd2\u0d82\u0dc4\u0dbd \u0dc1\u0dca\u200d\u0dbb\u0dc0\u0dca\u200d\u0dba \u0db4\u0dca\u200d\u0dbb\u0dad\u0dd2\u0dbd\u0dd3\u0db4\u0db1\u0dba\u0dba\u0dd2.");

    private final String code;
    private final String displayName;
    private final String initialPrompt;

    WhisperLanguage(String code, String displayName, String initialPrompt) {
        this.code = code;
        this.displayName = displayName;
        this.initialPrompt = initialPrompt;
    }

    public String code() { return code; }
    public String displayName() { return displayName; }
    public String initialPrompt() { return initialPrompt; }

    @Override
    public String toString() {
        return displayName;
    }
}
