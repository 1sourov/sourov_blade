package com.example.data.model

data class LocationPreferences(
    val country: String = "",
    val preferredLanguage: String = "",
    val preferredRegion: String = "",
    val preferredState: String = "",
    val preferredDivision: String = ""
) {
    val isIndia: Boolean
        get() = country.trim().equals("India", ignoreCase = true)

    val hasActiveIndiaFilters: Boolean
        get() = isIndia && (
            preferredLanguage.isNotBlank() ||
                preferredRegion.isNotBlank() ||
                preferredState.isNotBlank() ||
                preferredDivision.isNotBlank()
            )

    fun normalized(): LocationPreferences {
        val cleanCountry = LocationCatalog.canonicalizeCountry(country)
        return if (cleanCountry.equals("India", ignoreCase = true)) {
            LocationPreferences(
                country = "India",
                preferredLanguage = preferredLanguage.trim(),
                preferredRegion = preferredRegion.trim(),
                preferredState = preferredState.trim(),
                preferredDivision = preferredDivision.trim()
            )
        } else {
            LocationPreferences(
                country = cleanCountry,
                preferredLanguage = "",
                preferredRegion = "",
                preferredState = "",
                preferredDivision = ""
            )
        }
    }

    fun clearedIndiaFilters(): LocationPreferences = copy(
        preferredLanguage = "",
        preferredRegion = "",
        preferredState = "",
        preferredDivision = ""
    )
}

object LocationCatalog {
    val countries: List<String> = listOf(
        "India",
        "Bangladesh",
        "United States",
        "United Kingdom",
        "Canada",
        "Australia",
        "United Arab Emirates",
        "Saudi Arabia",
        "Pakistan",
        "Nepal",
        "Sri Lanka",
        "Singapore",
        "Malaysia",
        "Indonesia",
        "Japan",
        "South Korea",
        "Germany",
        "France",
        "Italy",
        "Spain",
        "Brazil",
        "Mexico",
        "Nigeria",
        "South Africa"
    )

    fun canonicalizeCountry(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return ""
        return countries.firstOrNull { it.equals(clean, ignoreCase = true) } ?: clean
    }

    fun sanitizedPostgrestCountryIlike(raw: String): String? {
        val clean = canonicalizeCountry(raw)
            .replace(Regex("[*,();:\\\\]"), "")
            .trim()
        if (clean.isBlank()) return null
        return "ilike.$clean"
    }

    val indiaLanguages: List<String> = listOf(
        "Bengali",
        "Hindi",
        "Tamil",
        "Telugu",
        "Marathi",
        "Gujarati",
        "Kannada",
        "Malayalam",
        "Punjabi",
        "Odia",
        "Assamese",
        "Urdu",
        "English"
    )

    val indiaRegions: List<String> = listOf(
        "East India",
        "North India",
        "South India",
        "West India",
        "Central India",
        "Northeast India"
    )

    val indiaStates: List<String> = listOf(
        "West Bengal",
        "Tamil Nadu",
        "Maharashtra",
        "Delhi",
        "Karnataka",
        "Telangana",
        "Andhra Pradesh",
        "Kerala",
        "Gujarat",
        "Rajasthan",
        "Uttar Pradesh",
        "Bihar",
        "Punjab",
        "Haryana",
        "Odisha",
        "Assam",
        "Jharkhand",
        "Chhattisgarh",
        "Madhya Pradesh",
        "Uttarakhand",
        "Himachal Pradesh",
        "Goa",
        "Jammu & Kashmir",
        "Tripura",
        "Manipur",
        "Meghalaya"
    )

    val indiaDivisionsByState: Map<String, List<String>> = mapOf(
        "West Bengal" to listOf("Presidency Division", "Burdwan Division", "Jalpaiguri Division", "Malda Division", "Medinipur Division", "Kolkata"),
        "Tamil Nadu" to listOf("Chennai", "Coimbatore", "Madurai", "Tiruchirappalli", "Salem"),
        "Maharashtra" to listOf("Konkan Division", "Pune Division", "Nashik Division", "Aurangabad Division", "Nagpur Division", "Amravati Division"),
        "Delhi" to listOf("Central Delhi", "North Delhi", "South Delhi", "East Delhi", "West Delhi", "New Delhi"),
        "Karnataka" to listOf("Bengaluru Division", "Mysuru Division", "Belagavi Division", "Kalaburagi Division"),
        "Telangana" to listOf("Hyderabad", "Warangal", "Nizamabad", "Karimnagar"),
        "Andhra Pradesh" to listOf("Coastal Andhra", "Rayalaseema", "Visakhapatnam", "Vijayawada"),
        "Kerala" to listOf("Thiruvananthapuram", "Kochi", "Kozhikode", "Thrissur"),
        "Gujarat" to listOf("Ahmedabad", "Surat", "Vadodara", "Rajkot"),
        "Uttar Pradesh" to listOf("Lucknow Division", "Varanasi Division", "Agra Division", "Meerut Division", "Prayagraj Division"),
        "Bihar" to listOf("Patna Division", "Tirhut Division", "Magadh Division", "Bhagalpur Division"),
        "Rajasthan" to listOf("Jaipur Division", "Jodhpur Division", "Udaipur Division", "Kota Division"),
        "Punjab" to listOf("Jalandhar Division", "Patiala Division", "Faridkot Division", "Ferozepur Division"),
        "Assam" to listOf("Lower Assam", "Upper Assam", "Central Assam", "Barak Valley")
    )

    fun divisionsForState(state: String): List<String> {
        val clean = state.trim()
        if (clean.isBlank()) {
            return listOf("Presidency Division", "Kolkata", "Chennai", "Konkan Division", "Bengaluru Division", "Lucknow Division")
        }
        return indiaDivisionsByState.entries
            .firstOrNull { it.key.equals(clean, ignoreCase = true) }
            ?.value
            ?: listOf("North Division", "South Division", "East Division", "West Division", "Central Division")
    }

    fun matchesVideo(
        video: VideoItem,
        prefs: LocationPreferences
    ): Boolean {
        val effectiveCountry = video.country.trim().ifBlank {
            video.creator?.country?.trim().orEmpty()
        }
        return matchesVideoFields(
            videoCountry = effectiveCountry,
            videoRegion = video.region,
            videoState = video.state,
            videoDivision = video.division,
            videoLanguage = video.language,
            prefs = prefs
        )
    }

    fun matchesVideoFields(
        videoCountry: String,
        videoRegion: String,
        videoState: String,
        videoDivision: String,
        videoLanguage: String,
        prefs: LocationPreferences
    ): Boolean {
        val normalized = prefs.normalized()
        if (normalized.country.isBlank()) return true
        if (!videoCountry.trim().equals(normalized.country, ignoreCase = true)) return false

        if (normalized.isIndia) {
            if (normalized.preferredLanguage.isNotBlank() &&
                !videoLanguage.trim().equals(normalized.preferredLanguage, ignoreCase = true)
            ) {
                return false
            }
            if (normalized.preferredRegion.isNotBlank() &&
                !videoRegion.trim().equals(normalized.preferredRegion, ignoreCase = true)
            ) {
                return false
            }
            if (normalized.preferredState.isNotBlank() &&
                !videoState.trim().equals(normalized.preferredState, ignoreCase = true)
            ) {
                return false
            }
            if (normalized.preferredDivision.isNotBlank() &&
                !videoDivision.trim().equals(normalized.preferredDivision, ignoreCase = true)
            ) {
                return false
            }
        }
        return true
    }
}
