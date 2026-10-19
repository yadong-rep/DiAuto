# XML custom view constructors and framework components only. Host R8 policy stays intact.
-keep public class com.hongqi.flighting.ui.CropSelectionView {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
