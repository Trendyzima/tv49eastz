package main

import (
	"context"
	"testing"
	"time"
)

func TestStudioFrameFanoutFeedsRecordingAndStreaming(t *testing.T) {
	fanout:=NewStudioFrameFanout(); recording:=&MemoryFrameSink{}; streaming:=&MemoryFrameSink{}
	if err:=fanout.Attach("recording",recording);err!=nil{t.Fatal(err)}
	if err:=fanout.Attach("streaming",streaming);err!=nil{t.Fatal(err)}
	frame:=StudioFrame{SessionID:"s1",SceneID:"program",Sequence:1,Payload:[]byte("frame")}
	if err:=fanout.Publish(context.Background(),frame);err!=nil{t.Fatal(err)}
	if len(recording.Frames)!=1||len(streaming.Frames)!=1{t.Fatalf("fanout did not feed both sinks")}
	if recording.Frames[0].Sequence!=streaming.Frames[0].Sequence{t.Fatalf("sinks received different program frames")}
}

func TestMemoryStudioSessionStoreExpires(t *testing.T){s:=NewMemoryStudioSessionStore(time.Second);v:=StudioRuntime{Spec:StudioSessionSpec{SessionID:"s1"},UpdatedAt:time.Unix(10,0)};if err:=s.Save(v);err!=nil{t.Fatal(err)};if n:=s.Expire(time.Unix(10,0).Add(2*time.Second));n!=1{t.Fatalf("expired=%d",n)};if _,ok:=s.Load("s1");ok{t.Fatal("session still present")}}
