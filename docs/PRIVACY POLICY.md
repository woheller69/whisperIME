### Table of Contents

- [Introduction](##Introduction)
- [Compliance with data regulations](##compliance-with-data-regulations)
- [Third party cloud service dependencies](##third-party-cloud-service-dependencies)
- [Data possibly processed by third party services](####data-possibly-processed-by-third-party-services)
- [Android permissions requested by the application](##android-permissions-requested-by-the-application)
- [License](##license)

## Introduction
This privacy policy covers ['Whisper'](https://github.com/woheller69/whisperIME) Android application.

It may not be applicable to other software produced or released by [woheller69](https://github.com/woheller69)

## Compliance with data regulations

Whisper is [GDPR](https://commission.europa.eu/law/law-topic/data-protection_en?), [HIPAA](https://www.hhs.gov/hipaa/index.html) and [CCPA](https://oag.ca.gov/privacy/ccpa/regs) privacy regulations compliant.

Whisper when running **does not:**

- Transmit any data whatsoever in any way, all voice recognition or translation operations are performed locally only on the user's device.

- Use, collect, store or share any statistics, personal information or analytics from its users, is devices or their use of these, other than Android operating system built in mechanisms that are present for all the mobile applications.

- Contain any advertising sdk, nor tracker of the user, his device or their use of these.

- Used, stored or shared cookies, at any point.

All external interactions require user action (pressing a button at least) by default.

## Third party service dependencies

**Note that Whisper:**

* Relies on The ["Hugging Face" Servers](https://huggingface.co) to retrieve AI models files usable (by users) to perform voice recognition and translation. Used directly _(locally)_ on the user's device, For this purpose only. Percase this service may store user information(s) and data(s) allowing identification. Please refer to the [Hugging Face's privacy policy](https://huggingface.co/privacy) for detailed information on how they handle user data.

#### Data possibly processed by third party services

__No data is sent to or otherwise shared with anyone. Data collected by third party services is by the operation of the device running Whisper without support or participation from 'Whisper'.__

The only known possible data leaks _(to the third-party servers)_ are the following:
1. User's device IP address
2. date and time stamp, time difference to GMT.
3. Information possibly available on the http header.
 
Third party services do not necessarily collect all of this data _(always refer to the service's privacy policy)_.

 <!-- Whisper specific licenses of libraries used in the application. - Not useful actually -->

## Android permissions requested by the application
Note that Whisper application **may require** the following android platform permissions:

* “INTERNET" android permission in order to be able to process downloading or updating models. Only at the explicit request of the user.
* “RECORD_AUDIO" android permission in order to perform voice recognition and translation.
* “FOREGROUND_SERVICE" android permission in order to use foreground services.
* “FOREGROUND_SERVICE_MICROPHONE" android permission necessary to properly use the microphone foreground services.
* “RECEIVE_BOOT_COMPLETED" android permission is useful for improving the application start time.

## License
[MIT License](https://mit-license.org/)

Copyright (c) 2025 woheller69
