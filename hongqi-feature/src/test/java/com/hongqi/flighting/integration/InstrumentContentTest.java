package com.hongqi.flighting.integration;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InstrumentContentTest {
    @Before public void reset() { InstrumentContent.clear(); }
    @Test public void progressAdvancesOnlyWhilePlayingAndStopsAtDuration() {
        InstrumentContent.metadata("歌", "歌手", null, 10000, 1000);
        InstrumentContent.playback(2000L,true,1000);
        assertEquals(5000,InstrumentContent.music().positionAt(4000));
        assertEquals(10000,InstrumentContent.music().positionAt(20000));
        InstrumentContent.playback(null,false,4000);
        assertEquals(5000,InstrumentContent.music().positionAt(20000));
    }
    @Test public void newSongClearsPreviousProgressAndArt() {
        InstrumentContent.metadata("旧歌", "歌手",new byte[]{1},10000,1000);
        InstrumentContent.playback(9000L,true,1000);
        InstrumentContent.metadata("新歌", "歌手",null,20000,2000);
        assertEquals(0,InstrumentContent.music().positionAt(2000));
        assertNull(InstrumentContent.music().artwork());
    }
    @Test public void repeatedMetadataDoesNotResetProgress() {
        InstrumentContent.metadata("歌", "歌手",null,10000,1000);
        InstrumentContent.playback(2000L,true,1000);
        InstrumentContent.metadata("歌", "歌手",null,10000,3000);
        assertEquals(4000,InstrumentContent.music().positionAt(3000));
    }
    @Test public void initialMetadataPreservesPlaybackThatArrivedFirst() {
        InstrumentContent.playback(3000L,true,1000);
        InstrumentContent.metadata("歌", "歌手",null,10000,2000);
        assertEquals(4000,InstrumentContent.music().positionAt(2000));
    }
    @Test public void artworkCannotBeMutatedByEitherProducerOrConsumer() {
        byte[] art={1};InstrumentContent.metadata("歌","歌手",art,10000,1000);
        art[0]=2;byte[] copy=InstrumentContent.music().artwork();copy[0]=3;
        assertArrayEquals(new byte[]{1},InstrumentContent.music().artwork());
    }
    @Test public void disconnectClearsAllIndependentPanels() {
        InstrumentContent.navigation(new InstrumentContent.Navigation(true,"←","左转","道路",300));
        InstrumentContent.metadata("歌","歌手",new byte[]{1},10000,1000);
        InstrumentContent.clear();
        assertFalse(InstrumentContent.navigation().active);
        assertEquals("",InstrumentContent.music().title);
        assertFalse(InstrumentContent.music().playing);
        assertNull(InstrumentContent.music().artwork());
    }
}
