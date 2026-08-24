/*
 * Copyright (C) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "dictionary/structure/v2/ver2_pt_node_array_reader.h"

#include "dictionary/structure/pt_common/patricia_trie_reading_utils.h"

namespace latinime {

bool Ver2PtNodeArrayReader::readPtNodeArrayInfoAndReturnIfValid(const int ptNodeArrayPos,
        int *const outPtNodeCount, int *const outFirstPtNodePos) const {
    if (ptNodeArrayPos < 0 || ptNodeArrayPos >= static_cast<int>(mBuffer.size())) {
        // Reading invalid position because of a bug or a broken dictionary.
        AKLOGE("Reading PtNode array info from invalid dictionary position: %d, dict size: %zd",
                ptNodeArrayPos, mBuffer.size());
        ASSERT(false);
        return false;
    }
    int readingPos = ptNodeArrayPos;
    const int remainingSize = static_cast<int>(mBuffer.size()) - readingPos;
    if (remainingSize < 1 || (mBuffer.data()[readingPos] >= 0x80 && remainingSize < 2)) {
        AKLOGE("Truncated PtNode count in an array at dictionary position: %d", ptNodeArrayPos);
        return false;
    }
    const int ptNodeCountInArray = PatriciaTrieReadingUtils::getPtNodeArraySizeAndAdvancePosition(
            mBuffer.data(), &readingPos);
    if (ptNodeCountInArray > static_cast<int>(mBuffer.size()) - readingPos) {
        AKLOGE("Invalid PtNode count in an array: %d.", ptNodeCountInArray);
        return false;
    }
    *outPtNodeCount = ptNodeCountInArray;
    *outFirstPtNodePos = readingPos;
    return true;
}

bool Ver2PtNodeArrayReader::readForwardLinkAndReturnIfValid(const int forwordLinkPos,
        int *const outNextPtNodeArrayPos) const {
    // Ver2 dictionaries have no forward links, including at the end of an array.
    *outNextPtNodeArrayPos = NOT_A_DICT_POS;
    return true;
}

} // namespace latinime
