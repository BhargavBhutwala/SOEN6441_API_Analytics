package controllers;

import Util.DescriptionUtil;
import Util.UserUtil;
import model.Canva;
import model.Project;
import model.Query;
import model.User;
import org.json.simple.parser.ParseException;
import play.i18n.MessagesApi;
import play.data.Form;
import play.data.FormFactory;
import play.libs.ws.WSClient;
import play.mvc.*;
import play.cache.*;
//import org.mockito.Mockito.*;
import Util.GeneralUtil;
import Util.StatsUtil;
import javax.inject.Inject;
import java.util.*;


import java.io.IOException;
import java.util.concurrent.ExecutionException;

/**
 * This controller contains an action to handle HTTP requests
 * to the application's home page.
 */
public class HomeController extends Controller {

    /**
     * Wsclient instance for asynchronous calling
     */
    private final WSClient ws;
    /**
     * FormFactory instance for creating form
     */
    private final FormFactory formFactory;
    /**
     * MessageApi instance for messages
     */
    private final MessagesApi messagesApi;
    /**
     * Cache instance for caching
     */
    private SyncCacheApi cache;

    /**
     * User and their list of canvas
     */
    private HashMap<String, List<Canva>> browsers;

    /**
     * Injects the required dependency using to inject of play framework
     * @param ws
     * @param formFactory
     * @param messagesApi
     * @param cache
     * @see Inject
     */
    @Inject
    public HomeController(WSClient ws, FormFactory formFactory, MessagesApi messagesApi, SyncCacheApi cache) {
        this.ws = ws;
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.browsers = new HashMap<>();
        this.cache = cache;
    }

    /**
     * When user calls the home method from <code>routes<code/>,it takes the session of user from the request and then
     * searches for it in the browsers list and then render the index view by passing the list of canva found in the browsers
     * @param request request call
     * @return Displays the home page of application with the recent searches
     * @author Sahil_40192697
     */
    public Result index(Http.Request request) {
        Optional<String> user = request.session().get("user");
        List<Canva> canvas = new ArrayList<>();
        Form<Query> queryForm = formFactory.form(Query.class);
        if (!user.isPresent()) {
            String id = GeneralUtil.generateId(this.browsers.keySet());
            this.browsers.put(id, new ArrayList<>());
            Collections.reverse(canvas);
            return ok(views.html.Home.index.render(canvas, queryForm, messagesApi.preferred(request))).addingToSession(request, "user", id);
        } else {
            if(this.browsers.get(user.get()) == null){
                this.browsers.put(user.get(), new ArrayList<>());
            }
            canvas.addAll(this.browsers.get(user.get()));
            Collections.reverse(canvas);
            return ok(views.html.Home.index.render(canvas, queryForm, messagesApi.preferred(request)));
        }
    }

    /**
     * When user enter the query in the search and submits this method id called and then it searches for the projects
     * in the cache.If found it is added to the session found from the request else calls to api.
     * @param request request call
     * @return redirects to index after adding the new search results to the canvas list of user in the session
     * @throws IOException
     * @throws ExecutionException
     * @throws InterruptedException
     * @throws ParseException
     * @author Sahil_40192697
     */
    public Result home(Http.Request request) throws IOException, ExecutionException, InterruptedException, ParseException {
        Form<Query> queryForm = formFactory.form(Query.class);
        Query q = queryForm.bindFromRequest(request).get();
        String url = "https://www.freelancer.com/api/projects/0.1/projects/active/";
        HashMap<String, String> params = new HashMap<>();
        params.put("query", q.getQuery());
        params.put("job_details", "true");
        params.put("compact", "false");
        params.put("limit", "10");

        String jsonResponse = GeneralUtil.getJsonResponseFromUrl(url, params, ws,cache);
        List<Project> projects = DescriptionUtil.getReadabilityIndex(GeneralUtil.getProjectsFromJson(jsonResponse));
        if (projects.size() == 0) {
            return ok(views.html.Home.error.render("No projects found"));
        }
        double averageIndex = DescriptionUtil.getAverageReadabilityIndex(projects);
        Canva c = new Canva(q.getQuery(), averageIndex, projects);
        Optional<String> user = request.session().get("user");
        if(!user.isPresent()){
            return ok(views.html.Home.error.render("no user found"));
        }
        List<Canva> clist = this.browsers.get(user.get());
        boolean checkIfPresent = clist.stream().map(Canva::getTitle).anyMatch(r -> r.equals(c.getTitle()));

        if(checkIfPresent){
            return redirect(routes.HomeController.index());
        }
        if (clist.size() == 10) {
            clist.clear();
        }
        clist.add(c);
        this.browsers.replace(user.get(), clist);
        return redirect(routes.HomeController.index());
    }

    /**
     * This method is invoked when the User clicks on any of the listed skills of the projects listed on the home page
     * It redirects to a page which contains the latest active projects' information, which is displayed in the same format as the homepage.
     *
     * @param jobId The Unique identifier for the Job category, which is used to search for a specific job.
     * @return Displays the latest active projects which contain the selected skill, with the limit capped at 10
     * @throws IOException          If the data is not upto the specific requirements of the system
     * @throws ParseException       If the system encounters an error during the parsing of the API
     * @throws ExecutionException   If the system fails to retrieve the necessary data while executing the requests.
     * @throws InterruptedException If the runtime is halted/hindered by some unforeseen reason.
     */
    public Result skills(String jobId) throws IOException, ParseException, ExecutionException, InterruptedException {
        String url = "https://www.freelancer.com/api/projects/0.1/projects/active/";
        HashMap<String, String> params = new HashMap<>();
        params.put("job_details", "true");
        params.put("compact", "false");
        params.put("jobs[]", jobId);
        params.put("limit", "10");

        String jsonResponse = GeneralUtil.getJsonResponseFromUrl(url, params, ws,cache);


        List<Project> projects = DescriptionUtil.getReadabilityIndex(GeneralUtil.getProjectsFromJson(jsonResponse));
        Double averageIndex = DescriptionUtil.getAverageReadabilityIndex(projects);

        return ok(views.html.Home.skills.render(projects, averageIndex));


    }

    /**
     * This method is invoked when the User clicks on owner_id field on the home page
     * It redirects to a page which contains information about the user.
     *
     * @param id owner_id that is unique for each user, and it will fetch user's data based on its owner_id
     * @return displays the page that contains information about the user as well as their last 10 projects
     * @throws IOException    thrown when an I/O error occurs.
     * @throws ParseException Signals that an error has been reached unexpectedly while parsing
     * @author Bhargav Bhutwala 40196468
     */
    public Result user(String id) throws IOException, ParseException, ExecutionException, InterruptedException {
        String url = "https://www.freelancer.com/api/users/0.1/users/" + id;
        String jsonRespone = GeneralUtil.getJsonResponseFromUrl(url, null, ws,cache);
        User user = UserUtil.getUserFromJson(jsonRespone, ws,cache);
        return ok(views.html.Home.user.render(user));
    }

    /**
     * Action for displaying statistics of 250 projects for a given query.
     *
     * @param query Search query to show statistics for
     * @return Renders statistics html page
     * @throws IOException
     * @throws ParseException
     * @author Harsh
     */
    public Result globalstats(String query) throws IOException, ParseException, ExecutionException, InterruptedException {
        List<String> result = new ArrayList<>();
        String encodeQuery = java.net.URLEncoder.encode(query, "UTF-8");

        String url = "https://www.freelancer.com/api/projects/0.1/projects/active/";
        HashMap<String, String> params = new HashMap<>();
        params.put("query", encodeQuery);
        params.put("sort_field", "time_updated");
        params.put("compact", "false");
        params.put("offset", "0");
        String response1 = GeneralUtil.getJsonResponseFromUrl(url, params, ws,cache);
        params.remove("offset");
        params.put("offset", "100");
        String response2 = GeneralUtil.getJsonResponseFromUrl(url, params, ws,cache);
        params.remove("offset");
        params.put("offset", "200");
        params.put("limit", "50");
        String response3 = GeneralUtil.getJsonResponseFromUrl(url, params, ws,cache);
        result.addAll(GeneralUtil.getDescriptionFromJson(response1));
        result.addAll(GeneralUtil.getDescriptionFromJson(response2));
        result.addAll(GeneralUtil.getDescriptionFromJson(response3));
        Map<String, Long> stats = StatsUtil.getStats(result);
        return ok(views.html.Home.stats.render(StatsUtil.sortStats(stats)));
    }

    /**
     * Action for displaying statistics of a given project.
     *
     * @param description preview_description of a project
     * @return Renders page displaying statistics for a single project
     * @throws IOException
     * @throws ParseException
     */
    public Result indistats(String description) throws IOException, ParseException {
        List<String> result = new ArrayList<>();
        result.add(description);
        Map<String, Long> stats = StatsUtil.getStats(result);
        return ok(views.html.Home.stats.render(StatsUtil.sortStats(stats)));
    }

}
