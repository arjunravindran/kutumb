# --- Enum names are data ---------------------------------------------------------------------
#
# Gender, RelationshipType and RelativeKind are persisted *by name*: in the database, in exported
# .ftree files, in a preference (KinshipLanguage), and — for RelativeKind — inside a navigation
# route that Navigation resolves by fully qualified class name. If R8 renames any of them, previously stored values stop matching and an
# exported file becomes unreadable — a silent data-loss bug that only appears in release builds.
#
# Found the hard way: the first signed build crashed on launch with
# "Cannot find class with name com.vibethroughcode.ftree.data.RelativeKind".
-keep class com.vibethroughcode.ftree.data.Gender { *; }
-keep class com.vibethroughcode.ftree.data.RelationshipType { *; }
-keep class com.vibethroughcode.ftree.data.RelativeKind { *; }
-keep class com.vibethroughcode.ftree.data.ParentKind { *; }
-keep class com.vibethroughcode.ftree.data.SpouseKind { *; }
-keep class com.vibethroughcode.ftree.data.SiblingKind { *; }
-keep class com.vibethroughcode.ftree.data.DeletionMode { *; }
-keep class com.vibethroughcode.ftree.data.KinshipLanguage { *; }
-keep class com.vibethroughcode.ftree.reminders.ReminderLead { *; }

# The same rule for what the family network and the trees store by name: the contact's pairing method,
# a place's label and source, a message's delivery status, a reward and its status (the Room tables),
# and a tree's kind (the catalogue, read back at every launch - a kind that failed to read would put
# a person back on their first tree without a word). A release build was exercised across a restart
# and read them all back; these lines keep it that way when R8's choices change.
-keep class com.vibethroughcode.ftree.kutumb.trust.PairingMethod { *; }
-keep class com.vibethroughcode.ftree.kutumb.geo.LocationLabel { *; }
-keep class com.vibethroughcode.ftree.kutumb.geo.LocationSource { *; }
-keep class com.vibethroughcode.ftree.kutumb.messaging.DeliveryStatus { *; }
-keep class com.vibethroughcode.ftree.kutumb.game.RewardType { *; }
-keep class com.vibethroughcode.ftree.kutumb.game.RedemptionStatus { *; }
-keep class com.vibethroughcode.ftree.data.TreeKind { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- kotlinx-serialization -------------------------------------------------------------------
#
# A @Serializable class gets a companion `serializer()` that is looked up reflectively. R8 cannot
# see that use, so without these the export format would fail to serialise in a release build while
# working perfectly in debug.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# The export document and the navigation routes are both serialized by type.
-keep class com.vibethroughcode.ftree.transfer.** { *; }
-keep class com.vibethroughcode.ftree.ui.*Route { *; }
-keep class com.vibethroughcode.ftree.ui.*Route$* { *; }

# --- the family book (#200) -------------------------------------------------------------------
#
# The Book is read from the composer's JSON by kotlinx-serialization, polymorphically on "t", and
# the policy switch reads policy.json the same way; R8 renaming a field or a sealed subclass is a
# book that cannot be read in release builds only. Kept whole, as the export document is.
-keep class com.vibethroughcode.ftree.book.** { *; }
-keep class com.vibethroughcode.ftree.entitlement.** { *; }
# The WebView calls the bridge's methods by name. Stripped or renamed, the book never arrives.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
