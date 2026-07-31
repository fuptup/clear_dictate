#include "clear_dictate/WindowsAudioSessionCapture.h"

#include <exception>
#include <iostream>
#include <stdexcept>
#include <string>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    void TestCancelBeforeStartIsTerminalAndDoesNotOpenMicrophone()
    {
        clear_dictate::BoundedAudioBlockQueue queue(8, 160);
        clear_dictate::WindowsAudioSessionCapture capture(queue);

        capture.CancelAndJoinProducer();

        Require(capture.State() == clear_dictate::WindowsCaptureState::Cancelled, "Pre-start Cancel must become terminal.");
        Require(capture.Error() == clear_dictate::WindowsCaptureError::None, "User cancellation must not become a capture failure.");
        Require(capture.WaitForActivity(0) == clear_dictate::WindowsCaptureActivity::Terminal, "Pre-start Cancel must wake the consumer.");
        Require(
            capture.Start(L"") == clear_dictate::WindowsCaptureError::Cancelled,
            "A cancelled one-shot capture object must never open the microphone.");
    }

    void TestStopBeforeStartIsTerminalAndDoesNotOpenMicrophone()
    {
        clear_dictate::BoundedAudioBlockQueue queue(8, 160);
        clear_dictate::WindowsAudioSessionCapture capture(queue);

        capture.StopAndJoinProducer();

        Require(capture.State() == clear_dictate::WindowsCaptureState::Stopped, "Pre-start Stop must become terminal.");
        Require(capture.Error() == clear_dictate::WindowsCaptureError::None, "Pre-start Stop must not become a capture failure.");
        Require(capture.WaitForActivity(0) == clear_dictate::WindowsCaptureActivity::Terminal, "Pre-start Stop must wake the consumer.");
        Require(
            capture.Start(L"") == clear_dictate::WindowsCaptureError::InitializationFailed,
            "A stopped one-shot capture object must never open the microphone.");
    }

    void TestStartRejectsAQueueContainingPriorSessionAudio()
    {
        clear_dictate::BoundedAudioBlockQueue queue(8, 160);
        const float priorSessionSample = 0.5F;
        Require(
            queue.TryPushSamples(&priorSessionSample, 1, false) == clear_dictate::AudioBlockPushResult::Accepted,
            "The prior-session fixture should enter the queue.");

        clear_dictate::WindowsAudioSessionCapture capture(queue);
        Require(
            capture.Start(L"") == clear_dictate::WindowsCaptureError::InitializationFailed,
            "Capture must reject a queue containing prior-session audio without opening the microphone.");
        Require(capture.State() == clear_dictate::WindowsCaptureState::Constructed, "Rejected queue reuse must not begin capture.");

        capture.CancelAndJoinProducer();
        queue.DiscardAndScrub();
    }

    void TestSplitCancelRequestIsTerminalBeforeStart()
    {
        clear_dictate::BoundedAudioBlockQueue queue(8, 160);
        clear_dictate::WindowsAudioSessionCapture capture(queue);

        capture.RequestCancel();
        capture.JoinProducer();

        Require(capture.State() == clear_dictate::WindowsCaptureState::Cancelled, "A split pre-start Cancel must become terminal.");
        Require(capture.Error() == clear_dictate::WindowsCaptureError::None, "A split pre-start Cancel must not become a capture failure.");
        Require(
            capture.Start(L"") == clear_dictate::WindowsCaptureError::Cancelled,
            "A split pre-start Cancel must prevent later microphone activation.");
    }

    void TestSplitStopRequestIsTerminalBeforeStart()
    {
        clear_dictate::BoundedAudioBlockQueue queue(8, 160);
        clear_dictate::WindowsAudioSessionCapture capture(queue);

        capture.RequestStop();
        capture.JoinProducer();

        Require(capture.State() == clear_dictate::WindowsCaptureState::Stopped, "A split pre-start Stop must become terminal.");
        Require(capture.Error() == clear_dictate::WindowsCaptureError::None, "A split pre-start Stop must not become a capture failure.");
        Require(
            capture.Start(L"") == clear_dictate::WindowsCaptureError::InitializationFailed,
            "A split pre-start Stop must prevent later microphone activation.");
    }

    int RunAllTests()
    {
        TestCancelBeforeStartIsTerminalAndDoesNotOpenMicrophone();
        TestStopBeforeStartIsTerminalAndDoesNotOpenMicrophone();
        TestStartRejectsAQueueContainingPriorSessionAudio();
        TestSplitCancelRequestIsTerminalBeforeStart();
        TestSplitStopRequestIsTerminalBeforeStart();
        return 0;
    }
}

int main()
{
    try
    {
        return RunAllTests();
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate Windows capture lifecycle tests failed: " << exception.what() << '\n';
        return 1;
    }
}
