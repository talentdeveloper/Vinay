(function() {
    'use strict';
    angular.module('theHiveDirectives')
        .directive('logEntry', function($uibModal, TaskLogSrv, UserInfoSrv, NotificationSrv) {
            return {
                templateUrl: 'views/directives/log-entry.html',
                link: function(scope) {

                    // drop log
                    scope.dropLog = function() {
                        scope.deleteModal = $uibModal.open({
                            scope: scope,
                            templateUrl: 'views/directives/log-entry-delete.html',
                            size: ''
                        });
                    };

                    scope.confirmDropLog = function() {
                        TaskLogSrv.delete({
                            logId: scope.log.id
                        }).$promise.then(function() {
                            scope.deleteModal.dismiss();
                        });
                    };

                    scope.cancelDropLog = function() {
                        scope.deleteModal.dismiss();
                    };

                    scope.updateLog = function() {
                        return TaskLogSrv.update({
                            logId: scope.log.id
                        }, {message: scope.log.message}, function() {}, function(response) {
                            NotificationSrv.error('CaseTaskLog', response.data, response.status);
                        });
                    };

                    scope.isImage = function(contentType) {
                        return angular.isString(contentType) && contentType.indexOf('image') === 0;
                    };
                    scope.showImage = function() {
                        $uibModal.open({
                            template: '<img style="width:100%" src="./api/datastore/' + scope.log.attachment.id + '" alt="' + scope.log.attachment.name + '"></img>',
                            size: 'lg'
                        });
                    };
                    scope.getUserInfo = UserInfoSrv;
                },
                restrict: 'EA',
                scope: {
                    log: '='
                }
            };
        });
})();
