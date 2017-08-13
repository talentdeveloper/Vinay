/**
 * Controller for main page
 */
angular.module('theHiveControllers').controller('RootCtrl',
    function($scope, $rootScope, $uibModal, $location, $state, AuthenticationSrv, AlertingSrv, StreamSrv, StreamStatSrv, TemplateSrv, CustomFieldsCacheSrv, MetricsCacheSrv, NotificationSrv, AppLayoutSrv, currentUser, appConfig) {
        'use strict';

        if(currentUser === 520) {
            $state.go('maintenance');
            return;
        }else if(!currentUser || !currentUser.id) {
            $state.go('login');
            return;
        }

        $rootScope.layoutSrv = AppLayoutSrv;
        $scope.appConfig = appConfig;

        $scope.querystring = '';
        $scope.view = {
            data: 'mytasks'
        };
        $scope.mispEnabled = false;
        $scope.customFieldsCache = [];

        StreamSrv.init();
        $scope.currentUser = currentUser;

        $scope.templates = TemplateSrv.query();

        $scope.myCurrentTasks = StreamStatSrv({
            scope: $scope,
            rootId: 'any',
            query: {
                '_and': [{
                    'status': 'InProgress'
                }, {
                    'owner': $scope.currentUser.id
                }]
            },
            result: {},
            objectType: 'case_task',
            field: 'status'
        });

        $scope.waitingTasks = StreamStatSrv({
            scope: $scope,
            rootId: 'any',
            query: {
                'status': 'Waiting'
            },
            result: {},
            objectType: 'case_task',
            field: 'status'
        });

        // Get metrics cache
        MetricsCacheSrv.all().then(function(list) {
            $scope.metricsCache = list;
        });

        // Get Alert counts
        $scope.alertEvents = AlertingSrv.stats($scope);

        $scope.$on('templates:refresh', function(){
            $scope.templates = TemplateSrv.query();
        });

        $scope.$on('metrics:refresh', function() {
            // Get metrics cache
            MetricsCacheSrv.all().then(function(list) {
                $scope.metricsCache = list;
            });
        });

        $scope.$on('custom-fields:refresh', function() {
            // Get custom fields cache
            $scope.initCustomFieldsCache();
        });

        $scope.$on('alert:event-imported', function() {
            $scope.alertEvents = AlertingSrv.stats($scope);
        });

        // FIXME
        // $scope.$on('misp:status-updated', function(event, enabled) {
        //     $scope.mispEnabled = enabled;
        // });

        $scope.initCustomFieldsCache = function() {
            CustomFieldsCacheSrv.all().then(function(list) {
                $scope.customFieldsCache = list;
            });
        };
        $scope.initCustomFieldsCache();

        $scope.isAdmin = function(user) {
            var u = user;
            var re = /admin/i;
            return re.test(u.roles);
        };

        $scope.selectView = function(name) {
            $state.go('app.main', {
                viewId: name
            });
            $scope.view.data = name;
        };

        $scope.logout = function() {
            AuthenticationSrv.logout(function() {
                $state.go('login');
            }, function(data, status) {
                NotificationSrv.error('RootCtrl', data, status);
            });
        };

        $scope.createNewCase = function(template) {
            $uibModal.open({
                templateUrl: 'views/partials/case/case.creation.html',
                controller: 'CaseCreationCtrl',
                size: 'lg',
                resolve: {
                    template: template
                }
            });
        };

        $scope.aboutTheHive = function() {
            $uibModal.open({
                templateUrl: 'views/partials/about.html',
                controller: 'AboutCtrl',
                size: ''
            });
        };

        $scope.search = function(querystring) {
            var query = Base64.encode(angular.toJson({
                _string: querystring
            }));

            $state.go('app.search', {
                q: query
            });
        };

        // Used to show spinning refresh icon n times
        $scope.getNumber = function(num) {
            return new Array(num);
        };
    }
);
