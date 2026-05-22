cordova.define("cordova-plugin-android-auto-navigation.AndroidAutoNav", function(require, exports, module) {
var exec = require('cordova/exec');

    exports.setRouteInfo = function (title, subtitle) {
        exec(null, null, 'AndroidAutoNavBridge', 'setRouteInfo', [{
            title: title || '',
            subtitle: subtitle || ''
        }]);
    };

    exports.setVehiclePosition = function (latitude, longitude) {
        exec(null, null, 'AndroidAutoNavBridge', 'setVehiclePosition', [{
            latitude: latitude,
            longitude: longitude
        }]);
    };

    exports.setFeaturePoints = function (points) {
        exec(null, null, 'AndroidAutoNavBridge', 'setFeaturePoints', [points || []]);
    };

    exports.updateFeaturePoints = function (addedPoints, removedPointIds, updatedPoints) {
        exec(null, null, 'AndroidAutoNavBridge', 'updateFeaturePoints', [
            addedPoints || [],
            removedPointIds || [],
            updatedPoints || []
        ]);
    };

    exports.setRotateWithHeading = function (enabled) {
        exec(null, null, 'AndroidAutoNavBridge', 'setRotateWithHeading', [{
            enabled: !!enabled
        }]);
    };

    exports.zoomIn = function () {
        exec(null, null, 'AndroidAutoNavBridge', 'zoomIn', []);
    };

    exports.zoomOut = function () {
        exec(null, null, 'AndroidAutoNavBridge', 'zoomOut', []);
    };

    exports.getVehicleLocation = function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'AndroidAutoNavBridge', 'getVehicleLocation', []);
    };

    exports.setQueryExtent = function (metres, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'AndroidAutoNavBridge', 'setQueryExtent', [{
            metres: metres
        }]);
    };

    exports.setLocationMode = function (mode, successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'AndroidAutoNavBridge', 'setLocationMode', [{
            mode: mode || 'real-vehicle'
        }]);
    };

    exports.getLocationMode = function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, 'AndroidAutoNavBridge', 'getLocationMode', []);
    };

module.exports = exports;

});
