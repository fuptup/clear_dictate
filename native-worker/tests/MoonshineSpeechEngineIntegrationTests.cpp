#include "clear_dictate\MoonshineSpeechEngine.h"

#include <algorithm>
#include <cstdint>
#include <exception>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace
{
    void Require(bool condition, const std::string& failureMessage)
    {
        if (!condition)
        {
            throw std::runtime_error(failureMessage);
        }
    }

    std::uint32_t ReadLittleEndian32(std::istream& input)
    {
        std::uint8_t bytes[4] {};
        input.read(reinterpret_cast<char*>(bytes), 4);
        return static_cast<std::uint32_t>(bytes[0]) |
            (static_cast<std::uint32_t>(bytes[1]) << 8) |
            (static_cast<std::uint32_t>(bytes[2]) << 16) |
            (static_cast<std::uint32_t>(bytes[3]) << 24);
    }

    std::uint16_t ReadLittleEndian16(std::istream& input)
    {
        std::uint8_t bytes[2] {};
        input.read(reinterpret_cast<char*>(bytes), 2);
        return static_cast<std::uint16_t>(bytes[0]) |
            static_cast<std::uint16_t>(static_cast<std::uint16_t>(bytes[1]) << 8);
    }

    std::vector<float> ReadMono16KilohertzWave(const std::string& path)
    {
        std::ifstream input(path, std::ios::binary);
        Require(input.good(), "The spoken fixture could not be opened.");
        char riffHeader[12] {};
        input.read(riffHeader, 12);
        Require(std::string(riffHeader, 4) == "RIFF" && std::string(riffHeader + 8, 4) == "WAVE", "The fixture is not a wave file.");

        bool formatWasValidated = false;
        while (input.good())
        {
            char chunkIdentifier[4] {};
            input.read(chunkIdentifier, 4);
            if (input.gcount() != 4)
            {
                break;
            }
            const std::uint32_t chunkByteCount = ReadLittleEndian32(input);
            const std::string chunkName(chunkIdentifier, 4);
            if (chunkName == "fmt ")
            {
                Require(chunkByteCount >= 16 && chunkByteCount <= 4096, "The fixture has an invalid format chunk.");
                const std::uint16_t audioFormat = ReadLittleEndian16(input);
                const std::uint16_t channelCount = ReadLittleEndian16(input);
                const std::uint32_t sampleRate = ReadLittleEndian32(input);
                const std::uint32_t byteRate = ReadLittleEndian32(input);
                const std::uint16_t blockAlignment = ReadLittleEndian16(input);
                const std::uint16_t bitsPerSample = ReadLittleEndian16(input);
                Require(
                    audioFormat == 1 &&
                        channelCount == 1 &&
                        sampleRate == 16000 &&
                        byteRate == 32000 &&
                        blockAlignment == 2 &&
                        bitsPerSample == 16,
                    "The fixture must be mono 16-kilohertz 16-bit integer PCM.");
                input.seekg(chunkByteCount - 16 + (chunkByteCount & 1), std::ios::cur);
                formatWasValidated = true;
            }
            else if (chunkName == "data")
            {
                Require(formatWasValidated, "The fixture audio appeared before its format declaration.");
                Require(chunkByteCount % 2 == 0, "The fixture does not contain 16-bit samples.");
                std::vector<std::int16_t> integerSamples(chunkByteCount / 2);
                input.read(reinterpret_cast<char*>(integerSamples.data()), chunkByteCount);
                Require(input.good(), "The fixture audio is truncated.");
                std::vector<float> samples(integerSamples.size());
                std::transform(
                    integerSamples.begin(),
                    integerSamples.end(),
                    samples.begin(),
                    [](std::int16_t sample)
                    {
                        return static_cast<float>(sample) / 32768.0F;
                    });
                std::fill(integerSamples.begin(), integerSamples.end(), std::int16_t { 0 });
                return samples;
            }
            else
            {
                input.seekg(chunkByteCount + (chunkByteCount & 1), std::ios::cur);
            }
        }
        throw std::runtime_error("The fixture has no audio chunk.");
    }

    int RunIntegrationTest(const std::string& modelDirectory, const std::string& wavePath)
    {
        std::vector<float> samples = ReadMono16KilohertzWave(wavePath);
        clear_dictate::MoonshineSpeechEngine engine(modelDirectory);
        engine.Start();
        std::vector<clear_dictate::TranscriptDelta> observedDeltas;
        bool observedCompletedDelta = false;

        constexpr std::size_t AwkwardChunkSize = 1379;
        for (std::size_t sampleOffset = 0; sampleOffset < samples.size(); sampleOffset += AwkwardChunkSize)
        {
            const std::size_t remainingSamples = samples.size() - sampleOffset;
            engine.AddAudio(samples.data() + sampleOffset, std::min(AwkwardChunkSize, remainingSamples), 16000);
            if ((sampleOffset / AwkwardChunkSize) % 4 == 3)
            {
                std::vector<clear_dictate::TranscriptDelta> changedLines = engine.TranscribeChangedLines(false);
                if (!changedLines.empty())
                {
                    const std::string copiedText = changedLines.front().text;
                    observedCompletedDelta =
                        observedCompletedDelta ||
                        std::any_of(
                            changedLines.begin(),
                            changedLines.end(),
                            [](const clear_dictate::TranscriptDelta& delta) { return delta.isComplete; });
                    observedDeltas.insert(observedDeltas.end(), changedLines.begin(), changedLines.end());
                    Require(
                        engine.TranscribeChangedLines(false).empty(),
                        "An unchanged completed line must not be replayed as a new delta.");
                    Require(changedLines.front().text == copiedText, "Copied delta text must survive the next native call.");
                }
            }
        }

        std::vector<clear_dictate::TranscriptDelta> forcedDeltas = engine.TranscribeChangedLines(true);
        observedCompletedDelta =
            observedCompletedDelta ||
            std::any_of(
                forcedDeltas.begin(),
                forcedDeltas.end(),
                [](const clear_dictate::TranscriptDelta& delta) { return delta.isComplete; });
        observedDeltas.insert(observedDeltas.end(), forcedDeltas.begin(), forcedDeltas.end());
        if (observedCompletedDelta)
        {
            Require(
                engine.TranscribeChangedLines(false).empty(),
                "A completed line must not be replayed after its actual update.");
        }
        const std::string finalTranscript = engine.StopAndFinish();
        std::fill(samples.begin(), samples.end(), 0.0F);
        Require(!observedDeltas.empty(), "The spoken fixture produced no streaming transcript deltas.");
        Require(observedCompletedDelta, "The spoken fixture produced no completed streaming line.");
        Require(!finalTranscript.empty(), "The spoken fixture produced an empty final transcript.");
        Require(
            finalTranscript.find("best of times") != std::string::npos ||
                finalTranscript.find("worst of times") != std::string::npos,
            "The spoken fixture did not produce the expected Dickens phrase.");

        engine.Start();
        const std::vector<float> silence(3200, 0.0F);
        engine.AddAudio(silence.data(), silence.size(), 16000);
        engine.CancelAndDiscard();

        engine.Start();
        engine.AddAudio(silence.data(), silence.size(), 16000);
        Require(engine.StopAndFinish().empty(), "A clean stream after cancellation must not inherit the prior transcript.");
        return 0;
    }
}

int main(int argumentCount, char** argumentValues)
{
    try
    {
        if (argumentCount != 3)
        {
            throw std::runtime_error("Expected model-directory and spoken-wave arguments.");
        }
        return RunIntegrationTest(argumentValues[1], argumentValues[2]);
    }
    catch (const std::exception& exception)
    {
        std::cerr << "ClearDictate Moonshine integration tests failed: " << exception.what() << '\n';
        return 1;
    }
}
