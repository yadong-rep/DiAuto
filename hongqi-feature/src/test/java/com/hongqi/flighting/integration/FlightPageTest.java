package com.hongqi.flighting.integration;

import android.content.pm.ApplicationInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.hongqi.flighting.R;
import com.hongqi.flighting.ui.HongqiFeatureActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class FlightPageTest {
    @Test public void authorizedPageStartsWithOnlyDiAutoVideoControls() {
        RuntimeEnvironment.getApplication().getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        try (org.robolectric.android.controller.ActivityController<HongqiFeatureActivity> controller =
                 Robolectric.buildActivity(HongqiFeatureActivity.class)) {
            controller.create().start().resume();
            HongqiFeatureActivity activity=controller.get();
            assertFalse(activity.isFinishing());
            View cast=activity.findViewById(R.id.hongqi_layout_cast_home);
            assertTrue(contains(cast,"DiAuto 完整视频"));
            assertTrue(contains(cast,"开始飞屏"));
            assertTrue(contains(cast,"停止飞屏"));
            assertNull(activity.findViewById(R.id.hongqi_home_crop_selection_view));
        }
    }
    private boolean contains(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return true;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;
        }
        return false;
    }
}
