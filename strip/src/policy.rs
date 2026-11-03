//! What each choice keeps. Images use allowlists: a tag or property that is
//! not listed here is removed, so metadata nobody thought of does not leak.
//! Videos follow the doc's list for the middle choice (location, make, model,
//! software) and an allowlist for the strictest one.

/// The two choices that remove something.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Strip {
    /// Remove location and identifiers; keep dates and exposure settings.
    Identifiers,
    /// Remove all but what display needs.
    AllButDisplay,
}

/// How a piece of metadata is treated.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Class {
    /// Needed to show the file: kept in every choice.
    Display,
    /// Dates and exposure settings: kept unless the strictest choice.
    Info,
    /// Removed by both stripping choices.
    Drop,
}

impl Strip {
    pub fn keeps(self, class: Class) -> bool {
        match class {
            Class::Display => true,
            Class::Info => self == Strip::Identifiers,
            Class::Drop => false,
        }
    }
}

/// The TIFF directories inside EXIF.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Ifd {
    Zero,
    Exif,
    Interop,
}

pub(crate) const TAG_EXIF_IFD: u16 = 0x8769;
pub(crate) const TAG_INTEROP_IFD: u16 = 0xA005;

pub(crate) fn exif_tag(ifd: Ifd, tag: u16) -> Class {
    use Class::*;
    match ifd {
        Ifd::Zero => match tag {
            // ImageWidth, ImageLength, BitsPerSample, Compression,
            // PhotometricInterpretation, Orientation, SamplesPerPixel,
            // X/YResolution, ResolutionUnit, TransferFunction, WhitePoint,
            // PrimaryChromaticities, YCbCrCoefficients, YCbCrSubSampling,
            // YCbCrPositioning, ReferenceBlackWhite.
            0x0100 | 0x0101 | 0x0102 | 0x0103 | 0x0106 | 0x0112 | 0x0115 | 0x011A | 0x011B
            | 0x0128 | 0x012D | 0x013E | 0x013F | 0x0211 | 0x0212 | 0x0213 | 0x0214 => Display,
            // DateTime.
            0x0132 => Info,
            _ => Drop,
        },
        Ifd::Exif => match tag {
            // ExifVersion, ComponentsConfiguration, FlashpixVersion,
            // ColorSpace, PixelX/YDimension, Gamma.
            0x9000 | 0x9101 | 0xA000 | 0xA001 | 0xA002 | 0xA003 | 0xA500 => Display,
            // DateTimeOriginal, DateTimeDigitized, OffsetTime*, SubSecTime*.
            0x9003 | 0x9004 | 0x9010 | 0x9011 | 0x9012 | 0x9290 | 0x9291 | 0x9292 => Info,
            // Exposure: ExposureTime, FNumber, ExposureProgram,
            // SpectralSensitivity, ISO, the sensitivity tags.
            0x829A | 0x829D | 0x8822 | 0x8824 | 0x8827 | 0x8830..=0x8835 => Info,
            // CompressedBitsPerPixel, ShutterSpeed .. FocalLength, SubjectArea.
            0x9102 | 0x9201..=0x920A | 0x9214 => Info,
            // FlashEnergy, FocalPlane*, SubjectLocation, ExposureIndex,
            // SensingMethod, FileSource, SceneType, CFAPattern.
            0xA20B | 0xA20E | 0xA20F | 0xA210 | 0xA214 | 0xA215 | 0xA217 | 0xA300 | 0xA301
            | 0xA302 => Info,
            // CustomRendered .. SubjectDistanceRange (not 0xA40B,
            // DeviceSettingDescription), LensSpecification, the composite
            // image tags.
            0xA401..=0xA40A | 0xA40C | 0xA432 | 0xA460..=0xA462 => Info,
            _ => Drop,
        },
        Ifd::Interop => match tag {
            // InteropIndex, InteropVersion, RelatedImageFileFormat/Width/Height.
            0x0001 | 0x0002 | 0x1000 | 0x1001 | 0x1002 => Display,
            _ => Drop,
        },
    }
}

pub(crate) mod ns {
    pub const RDF: &str = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    pub const X: &str = "adobe:ns:meta/";
    pub const XML: &str = "http://www.w3.org/XML/1998/namespace";
    pub const XMP_NOTE: &str = "http://ns.adobe.com/xmp/note/";
    pub const TIFF: &str = "http://ns.adobe.com/tiff/1.0/";
    pub const EXIF: &str = "http://ns.adobe.com/exif/1.0/";
    pub const EXIF_EX: &str = "http://cipa.jp/exif/1.0/";
    pub const XMP: &str = "http://ns.adobe.com/xap/1.0/";
    pub const PHOTOSHOP: &str = "http://ns.adobe.com/photoshop/1.0/";
    pub const HDRGM: &str = "http://ns.adobe.com/hdr-gain-map/1.0/";
    pub const G_CAMERA: &str = "http://ns.google.com/photos/1.0/camera/";
    pub const G_CONTAINER: &str = "http://ns.google.com/photos/1.0/container/";
    pub const G_ITEM: &str = "http://ns.google.com/photos/1.0/container/item/";
    pub const G_DEPTH: &str = "http://ns.google.com/photos/1.0/depthmap/";
    pub const G_IMAGE: &str = "http://ns.google.com/photos/1.0/image/";
    pub const G_PANO: &str = "http://ns.google.com/photos/1.0/panorama/";
    pub const G_AUDIO: &str = "http://ns.google.com/photos/1.0/audio/";
    pub const DYNAMIC_DEPTH: &str = "http://ns.google.com/photos/dd/1.0/";
    pub const APPLE_GAIN_MAP: &str = "http://ns.apple.com/HDRGainMap/1.0/";
    pub const APPLE_PIXEL_DATA: &str = "http://ns.apple.com/pixeldatainfo/1.0/";
}

/// Classifies an XMP property (an attribute or element that is not RDF
/// syntax), wherever it appears, nested structure fields included.
pub(crate) fn xmp_property(uri: &str, local: &str) -> Class {
    use Class::*;
    match uri {
        ns::XMP_NOTE => match local {
            "HasExtendedXMP" => Display,
            _ => Drop,
        },
        // Ultra HDR, its container and depth maps: structure, kept whole.
        ns::HDRGM | ns::G_CONTAINER | ns::G_ITEM | ns::G_DEPTH | ns::G_IMAGE => Display,
        ns::APPLE_GAIN_MAP | ns::APPLE_PIXEL_DATA => Display,
        ns::G_CAMERA => match local {
            "MotionPhoto"
            | "MotionPhotoVersion"
            | "MotionPhotoPresentationTimestampUs"
            | "MicroVideo"
            | "MicroVideoVersion"
            | "MicroVideoOffset"
            | "MicroVideoPresentationTimestampUs"
            | "SpecialTypeID"
            | "DisableAutoCreation"
            | "PortraitNote"
            | "PortraitVersion"
            | "PortraitRequest"
            | "RelitInputImageMime"
            | "RelitInputImageData"
            | "PortraitRelightingRenderingOptions"
            | "PortraitRelightingLightPos" => Display,
            // HDRPMakerNote, HDRPlusMakerNote, ShotLogData, BurstID,
            // GFileMetadata and anything new.
            _ => Drop,
        },
        ns::G_AUDIO => Display,
        ns::G_PANO => match local {
            "UsePanoramaViewer"
            | "ProjectionType"
            | "CroppedAreaImageWidthPixels"
            | "CroppedAreaImageHeightPixels"
            | "FullPanoWidthPixels"
            | "FullPanoHeightPixels"
            | "CroppedAreaLeftPixels"
            | "CroppedAreaTopPixels"
            | "InitialViewHeadingDegrees"
            | "InitialViewPitchDegrees"
            | "InitialViewRollDegrees"
            | "InitialHorizontalFOVDegrees"
            | "InitialVerticalFOVDegrees"
            | "PoseHeadingDegrees"
            | "PosePitchDegrees"
            | "PoseRollDegrees" => Display,
            "FirstPhotoDate" | "LastPhotoDate" => Info,
            _ => Drop,
        },
        ns::TIFF => match local {
            "Orientation"
            | "XResolution"
            | "YResolution"
            | "ResolutionUnit"
            | "ImageWidth"
            | "ImageLength"
            | "BitsPerSample"
            | "PhotometricInterpretation"
            | "SamplesPerPixel"
            | "YCbCrPositioning"
            | "YCbCrSubSampling" => Display,
            "DateTime" => Info,
            _ => Drop,
        },
        ns::EXIF => match local {
            "ExifVersion"
            | "FlashpixVersion"
            | "ColorSpace"
            | "PixelXDimension"
            | "PixelYDimension"
            | "ComponentsConfiguration" => Display,
            "DateTimeOriginal"
            | "DateTimeDigitized"
            | "ExposureTime"
            | "FNumber"
            | "ExposureProgram"
            | "ISOSpeedRatings"
            | "ShutterSpeedValue"
            | "ApertureValue"
            | "BrightnessValue"
            | "ExposureBiasValue"
            | "MaxApertureValue"
            | "SubjectDistance"
            | "MeteringMode"
            | "LightSource"
            | "Flash"
            | "Fired"
            | "Return"
            | "Mode"
            | "Function"
            | "RedEyeMode"
            | "FocalLength"
            | "FocalPlaneXResolution"
            | "FocalPlaneYResolution"
            | "FocalPlaneResolutionUnit"
            | "ExposureIndex"
            | "SensingMethod"
            | "FileSource"
            | "SceneType"
            | "CustomRendered"
            | "ExposureMode"
            | "WhiteBalance"
            | "DigitalZoomRatio"
            | "FocalLengthIn35mmFilm"
            | "SceneCaptureType"
            | "GainControl"
            | "Contrast"
            | "Saturation"
            | "Sharpness"
            | "SubjectDistanceRange"
            | "SubjectArea"
            | "SubjectLocation" => Info,
            _ => Drop,
        },
        ns::EXIF_EX => match local {
            "OffsetTime"
            | "OffsetTimeOriginal"
            | "OffsetTimeDigitized"
            | "PhotographicSensitivity"
            | "SensitivityType"
            | "StandardOutputSensitivity"
            | "RecommendedExposureIndex"
            | "ISOSpeed"
            | "LensSpecification" => Info,
            _ => Drop,
        },
        ns::XMP => match local {
            "CreateDate" | "ModifyDate" | "MetadataDate" => Info,
            _ => Drop,
        },
        ns::PHOTOSHOP => match local {
            "DateCreated" => Info,
            _ => Drop,
        },
        // Dynamic Depth: everything but the device pose (which can hold a
        // position on Earth), vendor info (make and model) and app info
        // (software).
        _ if uri.starts_with(ns::DYNAMIC_DEPTH) => match &uri[ns::DYNAMIC_DEPTH.len()..] {
            "pose/" | "vendorinfo/" | "appinfo/" => Drop,
            _ => Display,
        },
        _ => Drop,
    }
}

/// An MP4/QuickTime `udta` child or iTunes-style `ilst` item.
pub(crate) fn video_atom(strip: Strip, kind: &[u8; 4]) -> bool {
    // true: free it.
    match strip {
        Strip::AllButDisplay => true,
        Strip::Identifiers => matches!(
            kind,
            b"\xa9xyz"
                | b"loci"
                | b"\xa9mak"
                | b"\xa9mod"
                | b"\xa9swr"
                | b"\xa9too"
                | b"\xa9enc"
                | b"manu"
                | b"modl"
                // 3GPP and QuickTime author: the owner's name.
                | b"auth"
                | b"\xa9aut"
                | b"XMP_"
                | b"uuid"
        ),
    }
}

/// An `mdta` key (QuickTime and Android metadata keys). true: free its item.
pub(crate) fn video_key(strip: Strip, key: &str) -> bool {
    let key = key.to_ascii_lowercase();
    match strip {
        Strip::AllButDisplay => !matches!(
            key.as_str(),
            "com.android.capture.fps" | "com.android.video.temporal_layers_count"
        ),
        Strip::Identifiers => {
            // ffmpeg writes "location-eng"; strip a language suffix.
            let base = key.split('-').next().unwrap_or(&key);
            let last = base.rsplit('.').next().unwrap_or(base);
            base.contains("location")
                || base.starts_with("com.apple.quicktime.camera.")
                || base == "com.apple.quicktime.content.identifier"
                || matches!(
                    last,
                    "make" | "model" | "software" | "encoder" | "manufacturer"
                )
                || base == "com.android.version"
        }
    }
}
