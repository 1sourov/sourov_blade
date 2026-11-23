package com.example

import com.example.core.camera.VideoExportEngine
import com.example.core.moderation.FrameSkinMetrics
import com.example.core.moderation.ModerationResult
import com.example.core.moderation.NormalizedBox
import com.example.core.moderation.VideoContentModerator
import com.example.data.model.AuthUserDto
import com.example.data.model.AuthUserMetadataUpdateRequest
import com.example.data.model.CreateVideoLegacyRequest
import com.example.data.model.LikeWithUserRecord
import com.example.data.model.LocationCatalog
import com.example.data.model.LocationPreferences
import com.example.data.model.MessageItem
import com.example.data.model.NotificationItem
import com.example.data.model.SignInPhonePasswordRequest
import com.example.data.model.SignUpPhoneRequest
import com.example.data.model.UserProfile
import com.example.data.model.UserProfileLegacyUpsert
import com.example.data.model.VideoItem
import com.example.data.remote.SupabaseConfig
import com.example.data.repository.AuthInputValidator
import com.example.data.repository.BladeRepository
import com.example.data.repository.MissingCountryException
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun moshiKotlinAdapter_parsesSnakeCaseModels() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(VideoItem::class.java)
    val json = """
      {
        "id": "vid_1",
        "user_id": "usr_1",
        "video_url": "https://cdn.example.com/v.mp4",
        "title": "Test Blade",
        "likes_count": 42
      }
    """.trimIndent()
    val parsed = adapter.fromJson(json)
    assertEquals("vid_1", parsed?.id)
    assertEquals("usr_1", parsed?.userId)
    assertEquals(42, parsed?.likesCount)
  }

  @Test
  fun messageItem_parsesMediaFieldsAndResolvesDeliveryReadTicks() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(MessageItem::class.java)

    val sentVoice = adapter.fromJson("""
      {
        "id": "m1",
        "conversation_id": "c1",
        "sender_id": "u1",
        "client_id": "cid_1",
        "message_type": "voice",
        "media_url": "https://cdn.example.com/voice.m4a",
        "media_duration_ms": 6400,
        "status": "sent",
        "is_read": false
      }
    """.trimIndent())!!
    assertTrue(sentVoice.isVoiceMessage)
    assertEquals(6400L, sentVoice.mediaDurationMs)
    assertEquals("sent", sentVoice.resolvedStatus)

    val deliveredImage = adapter.fromJson("""
      {
        "id": "m2",
        "conversation_id": "c1",
        "sender_id": "u1",
        "message_type": "image",
        "media_url": "https://cdn.example.com/photo.jpg",
        "status": "delivered",
        "delivered_at": "2026-11-19T10:00:00Z",
        "is_read": false
      }
    """.trimIndent())!!
    assertTrue(deliveredImage.isImageMessage)
    assertEquals("delivered", deliveredImage.resolvedStatus)

    val readByTimestamp = MessageItem(
      id = "m3",
      status = "delivered",
      isRead = false,
      readAt = "2026-11-19T10:01:00Z"
    )
    assertEquals("read", readByTimestamp.resolvedStatus)
    assertEquals("sending", MessageItem(id = "local_1", status = "sending").resolvedStatus)
    assertEquals("failed", MessageItem(id = "local_2", status = "failed").resolvedStatus)
  }

  @Test
  fun messageItem_legacySchemaRoundTrip_decodesTextImageAndVoice() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(MessageItem::class.java)

    val legacyText = adapter.fromJson("""
      {
        "id": "m_leg_1",
        "conversation_id": "c1",
        "sender_id": "u1",
        "content": "Hello legacy!",
        "is_read": false,
        "created_at": "2026-11-19T10:00:00Z"
      }
    """.trimIndent())!!.normalized()
    assertEquals("text", legacyText.messageType)
    assertEquals("Hello legacy!", legacyText.content)
    assertEquals("sent", legacyText.resolvedStatus)

    val encodedImageWithCaption = MessageItem.encodeLegacyContent(
      messageType = "image",
      content = "Look at this!",
      mediaUrl = "https://cdn.example.com/pic.jpg",
      mediaDurationMs = 0L
    )
    val decodedImageWithCaption = MessageItem(
      id = "m_leg_2",
      content = encodedImageWithCaption,
      isRead = true
    ).normalized()
    assertTrue(decodedImageWithCaption.isImageMessage)
    assertEquals("https://cdn.example.com/pic.jpg", decodedImageWithCaption.mediaUrl)
    assertEquals("Look at this!", decodedImageWithCaption.content)
    assertEquals("read", decodedImageWithCaption.resolvedStatus)

    val encodedImageOnly = MessageItem.encodeLegacyContent(
      messageType = "image",
      content = "",
      mediaUrl = "https://cdn.example.com/only.png",
      mediaDurationMs = 0L
    )
    val decodedImageOnly = MessageItem(id = "m_leg_3", content = encodedImageOnly).normalized()
    assertTrue(decodedImageOnly.isImageMessage)
    assertEquals("https://cdn.example.com/only.png", decodedImageOnly.mediaUrl)
    assertEquals("", decodedImageOnly.content)

    val encodedVoice = MessageItem.encodeLegacyContent(
      messageType = "voice",
      content = "",
      mediaUrl = "https://cdn.example.com/voice.m4a",
      mediaDurationMs = 4850L
    )
    val decodedVoice = MessageItem(id = "m_leg_4", content = encodedVoice).normalized()
    assertTrue(decodedVoice.isVoiceMessage)
    assertEquals("https://cdn.example.com/voice.m4a", decodedVoice.mediaUrl)
    assertEquals(4850L, decodedVoice.mediaDurationMs)
    assertEquals("", decodedVoice.content)
  }

  @Test
  fun userProfile_resolvedVerificationStatus_handlesAllStatesAndMoshiParsing() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(UserProfile::class.java)

    val verifiedJson = """
      {
        "id": "u1",
        "username": "creator1",
        "is_verified": true,
        "verification_status": "verified"
      }
    """.trimIndent()
    val verifiedUser = adapter.fromJson(verifiedJson)!!
    assertEquals("verified", verifiedUser.resolvedVerificationStatus)

    val rejectedJson = """
      {
        "id": "u2",
        "username": "creator2",
        "is_verified": false,
        "verification_status": "REJECTED"
      }
    """.trimIndent()
    val rejectedUser = adapter.fromJson(rejectedJson)!!
    assertEquals("rejected", rejectedUser.resolvedVerificationStatus)

    val legacyPendingUser = UserProfile(id = "u3", username = "creator3", isVerified = false, verificationStatus = null)
    assertEquals("pending", legacyPendingUser.resolvedVerificationStatus)

    val verifiedOverridesRejected = UserProfile(id = "u4", username = "creator4", isVerified = true, verificationStatus = "rejected")
    assertEquals("verified", verifiedOverridesRejected.resolvedVerificationStatus)
  }

  @Test
  fun normalizeSupabaseUrl_stripsSubpathsAndQuotes() {
    assertEquals(
      "https://abcdefghij.supabase.co",
      SupabaseConfig.normalizeSupabaseUrl("\"https://abcdefghij.supabase.co/rest/v1/\"")
    )
    assertEquals(
      "https://abcdefghij.supabase.co",
      SupabaseConfig.normalizeSupabaseUrl("abcdefghij.supabase.co/")
    )
  }

  @Test
  fun normalizeSupabaseUrl_convertsDashboardUrlToProjectHost() {
    assertEquals(
      "https://myprojref123.supabase.co",
      SupabaseConfig.normalizeSupabaseUrl("https://supabase.com/dashboard/project/myprojref123/editor")
    )
  }

  @Test
  fun videoContentModerator_allowsNormalVideoMetadataAndAvoidsFalsePositives() {
    val cricket = VideoContentModerator.checkMetadataSafety(
      title = "Sunset Cricket Match in Dhaka",
      caption = "Great classic pass and goal! #cricket #dhaka #analysis",
      location = "Dhaka, Bangladesh",
      fileName = "VID_20261116_cricket.mp4"
    )
    assertTrue(cricket is ModerationResult.Safe)

    val benignCases = listOf(
      Triple("Spicy Hot Video Recipe", "Street food challenge #food #spicy", "hot_video_recipe.mp4"),
      Triple("iPhone 18 Leak Video", "Tech news & rumor breakdown", "tech_leak_video.mp4"),
      Triple("Essex Video Walking Tour", "Morning walk in unisex salon street", "essex_video.mp4"),
      Triple("Cricket Finale", "Only fans of cricket understand this save", "2018+_highlights.mp4"),
      Triple("18+ Cooking Tips", "Quick Maggi recipe & notification demo", "noti_demo_magi.mp4"),
      Triple("Nude Lipstick Tutorial", "Soft glam makeup and naked cake baking", "nude_lipstick_look.mp4"),
      Triple("Ford Escort Rally", "Classic rally drift with paint stripper DIY", "ford_escort_drift.mp4"),
      Triple("Desi Bhabhi Kitchen", "Homemade biryani recipe at home", "kitchen_vlog.mp4")
    )
    for ((title, caption, file) in benignCases) {
      val res = VideoContentModerator.checkMetadataSafety(
        title = title,
        caption = caption,
        location = "Dhaka",
        fileName = file
      )
      assertTrue("Expected Safe for '$title' / '$file' but got $res", res is ModerationResult.Safe)
    }
  }

  @Test
  fun videoContentModerator_blocksExplicitPornMetadataAndFilename() {
    val blockedCaption = VideoContentModerator.checkMetadataSafety(
      title = "Check this out",
      caption = "Watch hot #xxx #porn video now",
      location = ""
    )
    assertTrue(blockedCaption is ModerationResult.Blocked)
    assertEquals(VideoContentModerator.BLOCKED_MESSAGE, (blockedCaption as ModerationResult.Blocked).reason)

    val blockedFile = VideoContentModerator.checkMetadataSafety(
      title = "My video",
      caption = "Fun clip",
      location = "",
      fileName = "leaked_sextape_18+.mp4"
    )
    assertTrue(blockedFile is ModerationResult.Blocked)

    val blockedObfuscated = VideoContentModerator.checkMetadataSafety(
      title = "Watch",
      caption = "Free p.o.r.n link on onlyfans",
      location = ""
    )
    assertTrue(blockedObfuscated is ModerationResult.Blocked)

    val blockedBangla = VideoContentModerator.checkMetadataSafety(
      title = "নতুন ভিডিও",
      caption = "ভাইরাল সেক্স ভিডিও",
      location = ""
    )
    assertTrue(blockedBangla is ModerationResult.Blocked)

    val blockedNudeClip = VideoContentModerator.checkMetadataSafety(
      title = "Secret clip",
      caption = "Watch nude video",
      location = "",
      fileName = "nude_leak.mp4"
    )
    assertTrue(blockedNudeClip is ModerationResult.Blocked)
  }

  @Test
  fun videoContentModerator_visualPixels_distinguishWarmRoomSelfieFromExplicitNudity() {
    val w = 80
    val h = 120
    val skinToneLight = rgb(212, 158, 124)
    val skinToneMid = rgb(188, 134, 100)
    val darkClothOrBg = rgb(22, 26, 36)
    val sunsetOrange = rgb(245, 115, 25)

    // 1. Uniform warm beige room wall / golden filter covering whole frame -> SAFE
    val beigeWall = IntArray(w * h) { rgb(200, 156, 122) }
    val wallMetrics = VideoContentModerator.analyzeFramePixels(beigeWall, w, h)
    assertTrue(wallMetrics.likelyUniformBackdrop)
    assertFalse(wallMetrics.isExplicitCandidate)

    // 2. Oversaturated orange sunset / spicy food frame -> SAFE
    val sunsetPixels = IntArray(w * h) { sunsetOrange }
    val sunsetMetrics = VideoContentModerator.analyzeFramePixels(sunsetPixels, w, h)
    assertFalse(sunsetMetrics.isExplicitCandidate)

    // 3. Close-up talking-head selfie (face fills center-upper area, clothed shirt below) -> SAFE
    val selfiePixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      if (x in 18 until 62 && y in 18 until 76) skinToneLight else darkClothOrBg
    }
    val detectedFace = listOf(NormalizedBox(left = 0.20f, top = 0.12f, right = 0.80f, bottom = 0.65f))
    val selfieMetrics = VideoContentModerator.analyzeFramePixels(selfiePixels, w, h, detectedFace)
    assertFalse(selfieMetrics.isExplicitCandidate)

    // 4. Genuine explicit non-face center nudity against dark surroundings with natural shading variance -> BLOCKED
    val explicitPixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      if (x in 8 until 72 && y in 18 until 110) {
        if ((x + y) % 3 == 0) skinToneMid else skinToneLight
      } else {
        darkClothOrBg
      }
    }
    val explicitMetrics = VideoContentModerator.analyzeFramePixels(explicitPixels, w, h)
    assertFalse(explicitMetrics.likelyUniformBackdrop)
    assertTrue(explicitMetrics.isExplicitCandidate)

    // 5. Realistic medium-shot explicit torso + pelvic exposure (~44% of frame, NOT 60%+) with natural shading -> BLOCKED
    val mediumShotExplicitPixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      if (x in 20 until 62 && y in 30 until 108) {
        if ((x + y) % 3 == 0) skinToneMid else skinToneLight
      } else {
        darkClothOrBg
      }
    }
    val mediumShotMetrics = VideoContentModerator.analyzeFramePixels(mediumShotExplicitPixels, w, h)
    assertFalse(mediumShotMetrics.likelyUniformBackdrop)
    assertTrue(
      "Expected medium-shot explicit frame to be flagged, metrics=$mediumShotMetrics",
      mediumShotMetrics.isExplicitCandidate
    )

    // 6. Close-up textured explicit skin touching frame borders (skinLumaStdDev >= 15) must NOT be treated as uniform wall -> BLOCKED
    val closeUpTexturedSkin = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      val darkerSkin = rgb(132, 90, 64)
      if ((x / 4 + y / 4) % 2 == 0) skinToneLight else darkerSkin
    }
    val closeUpMetrics = VideoContentModerator.analyzeFramePixels(closeUpTexturedSkin, w, h)
    assertFalse("Close-up textured skin should not be whitelisted as uniform wall", closeUpMetrics.likelyUniformBackdrop)
    assertTrue("Expected close-up textured explicit skin to be flagged, metrics=$closeUpMetrics", closeUpMetrics.isExplicitCandidate)

    // 7. Darker skin in dim indoor lighting -> still caught when explicit
    val dimDarkExplicitPixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      if (x in 14 until 66 && y in 24 until 106) {
        if ((x + y) % 2 == 0) rgb(96, 62, 46) else rgb(72, 46, 34)
      } else {
        rgb(14, 16, 22)
      }
    }
    val dimDarkMetrics = VideoContentModerator.analyzeFramePixels(dimDarkExplicitPixels, w, h)
    assertTrue("Expected dim/darker-skin explicit frame to be flagged, metrics=$dimDarkMetrics", dimDarkMetrics.isExplicitCandidate)

    // 8. Normal clothed vlog (face + small separated hands/forearms) -> SAFE
    val clothedVlogPixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      val inFace = x in 26 until 54 && y in 12 until 42
      val inLeftArm = x in 6 until 14 && y in 48 until 82
      val inRightArm = x in 66 until 74 && y in 48 until 82
      when {
        inFace || inLeftArm || inRightArm -> if ((x + y) % 3 == 0) skinToneMid else skinToneLight
        else -> rgb(38, 64, 112) // denim/shirt outfit
      }
    }
    val clothedVlogFace = listOf(NormalizedBox(left = 0.30f, top = 0.08f, right = 0.70f, bottom = 0.38f))
    val clothedVlogMetrics = VideoContentModerator.analyzeFramePixels(clothedVlogPixels, w, h, clothedVlogFace)
    assertFalse("Normal clothed vlog must stay SAFE, metrics=$clothedVlogMetrics", clothedVlogMetrics.isExplicitCandidate)

    // 9. Multi-frame verdict: 1 incidental skin-heavy frame out of 5 stays SAFE, >=3 explicit frames BLOCKED, and 8-frame batch works consistently
    val safeVideoVerdict = VideoContentModerator.evaluateFrameMetrics(
      listOf(wallMetrics, sunsetMetrics, selfieMetrics, explicitMetrics, clothedVlogMetrics)
    )
    assertTrue(safeVideoVerdict is ModerationResult.Safe)

    val blockedVideoVerdict = VideoContentModerator.evaluateFrameMetrics(
      listOf(explicitMetrics, mediumShotMetrics, closeUpMetrics, selfieMetrics, wallMetrics)
    )
    assertTrue(blockedVideoVerdict is ModerationResult.Blocked)
    assertEquals(VideoContentModerator.BLOCKED_MESSAGE, (blockedVideoVerdict as ModerationResult.Blocked).reason)

    val safeEightFrameVlog = VideoContentModerator.evaluateFrameMetrics(
      listOf(selfieMetrics, clothedVlogMetrics, wallMetrics, sunsetMetrics, clothedVlogMetrics, selfieMetrics, explicitMetrics, clothedVlogMetrics)
    )
    assertTrue("8-frame normal vlog with 1 incidental frame must remain SAFE", safeEightFrameVlog is ModerationResult.Safe)

    val blockedEightFrameAdult = VideoContentModerator.evaluateFrameMetrics(
      listOf(selfieMetrics, mediumShotMetrics, dimDarkMetrics, closeUpMetrics, wallMetrics, clothedVlogMetrics, mediumShotMetrics, selfieMetrics)
    )
    assertTrue("8-frame adult clip must be BLOCKED", blockedEightFrameAdult is ModerationResult.Blocked)

    // 10. Sportswear / gym outfit (sports bra + athletic shorts, bare midriff/arms/legs, face excluded) -> SAFE
    val gymBlueFabric = rgb(36, 98, 176)
    val sportswearPixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      val inBody = x in 20 until 60 && y in 12 until 114
      val inSportsBra = x in 22 until 58 && y in 36 until 54
      val inGymShorts = x in 20 until 60 && y in 66 until 90
      when {
        inSportsBra || inGymShorts -> gymBlueFabric
        inBody -> if ((x + y) % 3 == 0) skinToneMid else skinToneLight
        else -> rgb(28, 30, 36)
      }
    }
    val gymFace = listOf(NormalizedBox(left = 0.34f, top = 0.08f, right = 0.66f, bottom = 0.25f))
    val sportswearMetrics = VideoContentModerator.analyzeFramePixels(sportswearPixels, w, h, gymFace)
    assertFalse(
      "Sportswear/gym outfit must remain SAFE, metrics=$sportswearMetrics",
      sportswearMetrics.isExplicitCandidate
    )

    // 11. Beach swimwear / bikini with sunlit blue ocean + sandy shore background -> SAFE
    val swimFabricRed = rgb(210, 40, 60)
    val oceanBlue = rgb(40, 125, 195)
    val swimwearPixels = IntArray(w * h) { yx ->
      val x = yx % w
      val y = yx / w
      val inBody = x in 24 until 56 && y in 16 until 112
      val inSwimTop = x in 25 until 55 && y in 38 until 52
      val inSwimBottom = x in 24 until 56 && y in 66 until 84
      when {
        inSwimTop || inSwimBottom -> swimFabricRed
        inBody -> if ((x + y) % 3 == 0) skinToneMid else skinToneLight
        else -> oceanBlue
      }
    }
    val swimMetrics = VideoContentModerator.analyzeFramePixels(swimwearPixels, w, h, gymFace)
    assertFalse(
      "Beach swimwear/bikini clip must remain SAFE, metrics=$swimMetrics",
      swimMetrics.isExplicitCandidate
    )

    val eightFrameFitnessAndBeach = VideoContentModerator.evaluateFrameMetrics(
      listOf(
        sportswearMetrics,
        swimMetrics,
        clothedVlogMetrics,
        sportswearMetrics,
        swimMetrics,
        selfieMetrics,
        sportswearMetrics,
        swimMetrics
      )
    )
    assertTrue(
      "Sportswear and swimwear 8-frame video must remain SAFE",
      eightFrameFitnessAndBeach is ModerationResult.Safe
    )
  }

  @Test
  fun videoContentModerator_additionalFileNames_catchesOriginalExplicitFileWhenExportedGeneric() {
    val result = VideoContentModerator.checkMetadataSafety(
      title = "Weekend Clip",
      caption = "Uploaded from studio",
      location = "Dhaka",
      fileName = "blade_export_1732280000.mp4",
      additionalFileNames = listOf("pornhub_leaked_18+.mp4")
    )
    assertTrue(result is ModerationResult.Blocked)
    assertEquals(VideoContentModerator.BLOCKED_MESSAGE, (result as ModerationResult.Blocked).reason)
  }

  @Test
  fun validSoundUuidOrNull_acceptsRealUuidAndRejectsLocalDeviceIds() {
    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    assertEquals(uuid, BladeRepository.validSoundUuidOrNull(" $uuid "))
    assertNull(BladeRepository.validSoundUuidOrNull("device_sound_1732280000"))
    assertNull(BladeRepository.validSoundUuidOrNull(""))
    assertNull(BladeRepository.validSoundUuidOrNull(null))
  }

  @Test
  fun videoExportEngine_shouldStripOriginalAudio_stripsMicWhenCustomSoundSelected() {
    assertTrue(VideoExportEngine.shouldStripOriginalAudio(emptyList(), hasMusic = true))
    assertFalse(VideoExportEngine.shouldStripOriginalAudio(emptyList(), hasMusic = false))
  }

  @Test
  fun authInputValidator_validatesEmailsAndNormalizesPhones() {
    assertTrue(AuthInputValidator.isValidEmail("creator@blade.app"))
    assertFalse(AuthInputValidator.isValidEmail("not-an-email"))

    assertEquals("+14155551234", AuthInputValidator.normalizePhone("+1 (415) 555-1234"))
    assertEquals("+8801712345678", AuthInputValidator.normalizePhone("00880 1712-345678"))
    assertTrue(AuthInputValidator.isValidPhone("+14155551234"))
    assertFalse(AuthInputValidator.isValidPhone("12345"))
  }

  @Test
  fun phoneAuthDtos_serializeWithoutEmailKey() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val signInJson = moshi.adapter(SignInPhonePasswordRequest::class.java)
      .toJson(SignInPhonePasswordRequest(phone = "+14155551234", password = "secret-password"))
    assertTrue(signInJson.contains("\"phone\":\"+14155551234\""))
    assertFalse(signInJson.contains("\"email\""))

    val signUpJson = moshi.adapter(SignUpPhoneRequest::class.java)
      .toJson(
        SignUpPhoneRequest(
          phone = "+14155551234",
          password = "secret-password",
          data = mapOf("display_name" to "Araf")
        )
      )
    assertTrue(signUpJson.contains("\"phone\":\"+14155551234\""))
    assertFalse(signUpJson.contains("\"email\""))
  }

  @Test
  fun notificationAndLikerModels_parseVideoReferenceAndRealUserProfiles() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val notifAdapter = moshi.adapter(NotificationItem::class.java)
    val shareNotif = notifAdapter.fromJson(
      """
      {
        "id": "n1",
        "user_id": "creator_1",
        "actor_id": "viewer_1",
        "type": "share",
        "title": "Video Shared",
        "body": "shared your video",
        "video_id": "vid_99",
        "is_read": false
      }
      """.trimIndent()
    )!!
    assertEquals("share", shareNotif.type)
    assertEquals("vid_99", shareNotif.videoId)

    val likerAdapter = moshi.adapter(LikeWithUserRecord::class.java)
    val likeRecord = likerAdapter.fromJson(
      """
      {
        "video_id": "vid_99",
        "user_id": "viewer_1",
        "users": {
          "id": "viewer_1",
          "username": "nabila",
          "display_name": "Nabila K",
          "avatar_url": "https://cdn.example.com/a.jpg",
          "is_verified": true
        }
      }
      """.trimIndent()
    )!!
    assertEquals("vid_99", likeRecord.videoId)
    assertEquals("nabila", likeRecord.user?.username)
    assertEquals(true, likeRecord.user?.isVerified)
  }

  @Test
  fun locationCatalog_enforcesCountryAndAllOfIndiaVersusFilteredIndiaRules() {
    val wbBengali = VideoItem(
      id = "v_wb",
      country = "India",
      region = "East India",
      state = "West Bengal",
      division = "Kolkata",
      language = "Bengali"
    )
    val tnTamil = VideoItem(
      id = "v_tn",
      country = "India",
      region = "South India",
      state = "Tamil Nadu",
      division = "Chennai",
      language = "Tamil"
    )
    val indiaUntagged = VideoItem(
      id = "v_in_plain",
      country = "India"
    )
    val usVideo = VideoItem(
      id = "v_us",
      country = "United States"
    )

    // Unfiltered India user sees ALL OF INDIA (never restricted to one state/language)
    val allIndiaPrefs = LocationPreferences(country = "India")
    assertTrue(LocationCatalog.matchesVideo(wbBengali, allIndiaPrefs))
    assertTrue(LocationCatalog.matchesVideo(tnTamil, allIndiaPrefs))
    assertTrue(LocationCatalog.matchesVideo(indiaUntagged, allIndiaPrefs))
    assertFalse(LocationCatalog.matchesVideo(usVideo, allIndiaPrefs))

    // Filtered India + West Bengal + Bengali matches only that context
    val wbPrefs = LocationPreferences(
      country = "India",
      preferredState = "West Bengal",
      preferredLanguage = "Bengali"
    )
    assertTrue(LocationCatalog.matchesVideo(wbBengali, wbPrefs))
    assertFalse(LocationCatalog.matchesVideo(tnTamil, wbPrefs))
    assertFalse(LocationCatalog.matchesVideo(indiaUntagged, wbPrefs))
    assertFalse(LocationCatalog.matchesVideo(usVideo, wbPrefs))

    // Filtered India + Tamil Nadu + Tamil matches only that context
    val tnPrefs = LocationPreferences(
      country = "India",
      preferredState = "Tamil Nadu",
      preferredLanguage = "Tamil"
    )
    assertTrue(LocationCatalog.matchesVideo(tnTamil, tnPrefs))
    assertFalse(LocationCatalog.matchesVideo(wbBengali, tnPrefs))

    // Clearing India filters returns cleanly to ALL OF INDIA
    val cleared = wbPrefs.clearedIndiaFilters()
    assertFalse(cleared.hasActiveIndiaFilters)
    assertTrue(LocationCatalog.matchesVideo(wbBengali, cleared))
    assertTrue(LocationCatalog.matchesVideo(tnTamil, cleared))
    assertTrue(LocationCatalog.matchesVideo(indiaUntagged, cleared))

    // Non-India countries ignore India regional fields and filter strictly by Country
    val nonIndiaNormalized = LocationPreferences(
      country = "United States",
      preferredLanguage = "Bengali",
      preferredState = "West Bengal"
    ).normalized()
    assertEquals("", nonIndiaNormalized.preferredLanguage)
    assertEquals("", nonIndiaNormalized.preferredState)
    assertTrue(LocationCatalog.matchesVideo(usVideo, nonIndiaNormalized))
    assertFalse(LocationCatalog.matchesVideo(wbBengali, nonIndiaNormalized))
  }

  @Test
  fun locationMetadata_parsesAndNormalizesCountryFields() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val video = moshi.adapter(VideoItem::class.java).fromJson(
      """
      {
        "id": "v_meta",
        "user_id": "u_meta",
        "video_url": "https://cdn.example.com/v.mp4",
        "country": "India",
        "region": "East India",
        "state": "West Bengal",
        "division": "Presidency Division",
        "language": "Bengali"
      }
      """.trimIndent()
    )!!
    assertEquals("India", video.country)
    assertEquals("East India", video.region)
    assertEquals("West Bengal", video.state)
    assertEquals("Presidency Division", video.division)
    assertEquals("Bengali", video.language)

    val indiaNorm = LocationPreferences(country = " india ", preferredState = " Tamil Nadu ").normalized()
    assertEquals("India", indiaNorm.country)
    assertEquals("Tamil Nadu", indiaNorm.preferredState)
  }

  @Test
  fun supabaseErrorDetails_stripsNullHintAndDetectsSchemaCacheMissingColumn() {
    val rawPgrst204 = """
      {"code":"PGRST204","details":null,"hint":null,"message":"Could not find the 'country' column of 'users' in the schema cache"}
    """.trimIndent()
    val (code, detail) = BladeRepository.extractErrorDetails(rawPgrst204)
    assertEquals("PGRST204", code)
    assertEquals("Could not find the 'country' column of 'users' in the schema cache", detail)
    assertFalse(detail.contains("null"))
    assertTrue(BladeRepository.isMissingColumnSchemaError(rawPgrst204, 400))
    assertFalse(BladeRepository.isMissingColumnSchemaError("{\"message\":\"Unauthorized\"}", 401))

    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val legacyJson = moshi.adapter(UserProfileLegacyUpsert::class.java).toJson(
      UserProfileLegacyUpsert(
        id = "u1",
        username = "araf_u1",
        displayName = "Araf"
      )
    )
    assertFalse(legacyJson.contains("\"country\""))

    val authUser = moshi.adapter(AuthUserDto::class.java).fromJson(
      """
      {
        "id": "u1",
        "email": "a@b.com",
        "user_metadata": {
          "country": "India",
          "preferred_state": "West Bengal",
          "preferred_language": "Bengali"
        }
      }
      """.trimIndent()
    )!!
    assertEquals("India", authUser.userMetadata?.get("country"))
    assertEquals("West Bengal", authUser.userMetadata?.get("preferred_state"))

    val metaReqJson = moshi.adapter(AuthUserMetadataUpdateRequest::class.java).toJson(
      AuthUserMetadataUpdateRequest(mapOf("country" to "India"))
    )
    assertTrue(metaReqJson.contains("\"country\":\"India\""))
  }

  @Test
  fun countryAutoAssignmentAndBangladeshFeedFiltering_workCaseInsensitively() {
    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    val legacyJson = moshi.adapter(CreateVideoLegacyRequest::class.java).toJson(
      CreateVideoLegacyRequest(
        userId = "user_bd_1",
        videoUrl = "https://cdn.example.com/bd.mp4",
        caption = "Dhaka sunset",
        hashtags = listOf("dhaka"),
        category = "Travel",
        location = "",
        country = "Bangladesh",
        privacy = "public"
      )
    )
    assertTrue("Legacy upload payload must preserve country", legacyJson.contains("\"country\":\"Bangladesh\""))

    val creatorBd = UserProfile(id = "user_bd_1", username = "araf", country = "Bangladesh")
    val uploadedLowerBd = VideoItem(id = "vid_bd_1", userId = creatorBd.id, country = "bangladesh", creator = creatorBd)
    val uploadedCanonicalBd = VideoItem(id = "vid_bd_2", userId = creatorBd.id, country = "Bangladesh", creator = creatorBd)
    val legacyBlankVideoCountryFromBdCreator = VideoItem(id = "vid_bd_3", userId = creatorBd.id, country = "", creator = creatorBd)
    val otherCountryVideo = VideoItem(id = "vid_us_1", userId = "user_us_1", country = "United States")

    val userBdLowerPrefs = LocationPreferences(country = " bangladesh ").normalized()
    assertEquals("Bangladesh", userBdLowerPrefs.country)
    assertEquals("ilike.Bangladesh", LocationCatalog.sanitizedPostgrestCountryIlike("bangladesh"))
    assertNull(LocationCatalog.sanitizedPostgrestCountryIlike("   "))

    // Bangladesh users see Bangladesh videos (regardless of capitalization) and do NOT see other-country videos
    assertTrue(LocationCatalog.matchesVideo(uploadedLowerBd, userBdLowerPrefs))
    assertTrue(LocationCatalog.matchesVideo(uploadedCanonicalBd, userBdLowerPrefs))
    assertTrue(LocationCatalog.matchesVideo(legacyBlankVideoCountryFromBdCreator, userBdLowerPrefs))
    assertFalse(LocationCatalog.matchesVideo(otherCountryVideo, userBdLowerPrefs))

    // User from another country cannot see Bangladesh videos
    val userIndiaPrefs = LocationPreferences(country = "India").normalized()
    assertFalse(LocationCatalog.matchesVideo(uploadedCanonicalBd, userIndiaPrefs))
    assertFalse(LocationCatalog.matchesVideo(uploadedLowerBd, userIndiaPrefs))

    val missingErr = MissingCountryException("Country required")
    assertEquals("Country required", missingErr.message)
  }

  private fun rgb(r: Int, g: Int, b: Int): Int {
    return (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
  }
}
