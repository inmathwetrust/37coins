define([
	'backbone',
	'communicator',
	'views/headerView',
    'views/navView',
	'views/footerView'
],

function( Backbone, Communicator, HeaderView, NavView, FooterView) {
    'use strict';

	var App = new Backbone.Marionette.Application();

    // these regions correspond to #ID's in the index.html 
    App.addRegions({
        nav: '#nav',
        header: '#header',
        content: '#content',
        footer: 'footer'
    });

    // marionette app events...
    App.on('initialize:after', function() {
        if (Backbone.history){
            if (!Backbone.history.start({pushState: true})) {
                console.log('notFound');
                App.router.navigate('notFound', {trigger: true});
            }
        }
    });

	Communicator.mediator.on('app:show', function(appView,headerView) {
        if (headerView){
            App.header.show(headerView);
        }else{
            App.header.show(new HeaderView({model:new Backbone.Model({resPath:window.opt.resPath})}));
        }
        App.content.show(appView);
    });

    Communicator.mediator.on('app:logout', function() {
        //renavigate to see if we still have permission
        App.router.navigate('logout',{trigger: true});
    });

    App.getParameterByName = function(name) {
        name = name.replace(/[\[]/, '\\[').replace(/[\]]/, '\\]');
        var regex = new RegExp('[\\?&]' + name + '=([^&#]*)'),
        results = regex.exec(location.search);
        return results === null ? '' : decodeURIComponent(results[1].replace(/\+/g, ' '));
    };

	/* Add initializers here */
	App.addInitializer( function (options) {
        if (!App.getParameterByName('noHead')){
            App.nav.show(new NavView({model:new Backbone.Model({resPath:window.opt.resPath, basePath:window.opt.basePath})}));
            App.footer.show(new FooterView({model:new Backbone.Model({resPath:window.opt.resPath, basePath:window.opt.basePath})}));
        }else{
            $('div#nav').remove();
            $('div#header').remove();
            $('div#content').css('padding-top','5px');
            $('div#footer').remove();
        }
        this.router = new options.pageController.Router({
            controller: options.pageController, // wire-up the start method
            app:App
        });

        // Use delegation to avoid initial DOM selection and allow all matching elements to bubble
        $(document).delegate('a', 'click', function(evt) {
            // Get the anchor href and protcol
            var href = $(this).attr('href');
            // Ensure the protocol is not part of URL, meaning its relative. Stop the event bubbling to ensure the link will not cause a page refresh.
            if (href.indexOf('http://') === -1 && href.indexOf('https://') === -1 && href.indexOf('bitcoin:') === -1) {
                evt.preventDefault();
                // Note by using Backbone.history.navigate, router events will not be triggered.  If this is a problem, change this to navigate on your router.
                App.router.navigate(href, true);
            }
        });
	});

	return App;
});
