cordova.define('cordova/plugin_list', function(require, exports, module) {
  module.exports = [
    {
      "id": "cordova-plugin-geolocation.geolocation",
      "file": "plugins/cordova-plugin-geolocation/www/android/geolocation.js",
      "pluginId": "cordova-plugin-geolocation",
      "clobbers": [
        "navigator.geolocation"
      ]
    },
    {
      "id": "cordova-plugin-geolocation.PositionError",
      "file": "plugins/cordova-plugin-geolocation/www/PositionError.js",
      "pluginId": "cordova-plugin-geolocation",
      "runs": true
    },
    {
      "id": "cordova-plugin-android-auto-navigation.AndroidAutoNav",
      "file": "plugins/cordova-plugin-android-auto-navigation/www/androidautonav.js",
      "pluginId": "cordova-plugin-android-auto-navigation",
      "clobbers": [
        "window.plugins.androidAutoNav"
      ]
    }
  ];
  module.exports.metadata = {
    "cordova-plugin-geolocation": "5.0.0",
    "cordova-plugin-android-auto-navigation": "0.1.0"
  };
});